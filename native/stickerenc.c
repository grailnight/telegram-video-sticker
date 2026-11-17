#include "stickerenc.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "vpx/vp8cx.h"
#include "vpx/vpx_encoder.h"

#ifdef __ANDROID__
#include <android/log.h>
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "stickerenc", __VA_ARGS__)
#else
#define LOGE(...) (fprintf(stderr, __VA_ARGS__), fputc('\n', stderr))
#endif

/* ---------------------------------------------------------------- frame store */

struct se_store {
    int w, h;
    int cw, ch;
    int count, capacity;
    uint8_t **yuv;   /* I420: Y (w*h), U (cw*ch), V (cw*ch) */
    uint8_t **alpha; /* w*h, or NULL for a fully opaque frame */
    int has_alpha;
};

se_store *se_create(int width, int height) {
    if (width <= 0 || height <= 0 || (width & 1) || (height & 1)) return NULL;
    se_store *s = calloc(1, sizeof(*s));
    if (!s) return NULL;
    s->w = width;
    s->h = height;
    s->cw = width / 2;
    s->ch = height / 2;
    return s;
}

void se_destroy(se_store *s) {
    if (!s) return;
    for (int i = 0; i < s->count; i++) {
        free(s->yuv[i]);
        free(s->alpha[i]);
    }
    free(s->yuv);
    free(s->alpha);
    free(s);
}

int se_frame_count(const se_store *s) { return s ? s->count : 0; }
int se_has_alpha(const se_store *s) { return s ? s->has_alpha : 0; }

static inline uint8_t clamp8(int v) { return v < 0 ? 0 : (v > 255 ? 255 : (uint8_t)v); }

int se_add_rgba(se_store *s, const uint8_t *rgba, int bottom_up) {
    if (!s || !rgba) return SE_ERR_GENERIC;
    if (s->count == s->capacity) {
        int cap = s->capacity ? s->capacity * 2 : 32;
        uint8_t **y = realloc(s->yuv, cap * sizeof(*y));
        if (!y) return SE_ERR_MEMORY;
        s->yuv = y;
        uint8_t **a = realloc(s->alpha, cap * sizeof(*a));
        if (!a) return SE_ERR_MEMORY;
        s->alpha = a;
        s->capacity = cap;
    }

    const int w = s->w, h = s->h, cw = s->cw, ch = s->ch;
    uint8_t *yuv = malloc((size_t)w * h + 2 * (size_t)cw * ch);
    uint8_t *alpha = malloc((size_t)w * h);
    /* Straight (un-premultiplied) RGB of the two rows being processed, for chroma averaging. */
    uint8_t *rows = malloc((size_t)w * 2 * 3);
    if (!yuv || !alpha || !rows) {
        free(yuv);
        free(alpha);
        free(rows);
        return SE_ERR_MEMORY;
    }

    uint8_t *yp = yuv, *up = yuv + (size_t)w * h, *vp = up + (size_t)cw * ch;
    int transparent = 0;

    for (int y = 0; y < h; y += 2) {
        for (int k = 0; k < 2; k++) {
            int row = y + k;
            const uint8_t *src = rgba + (size_t)(bottom_up ? h - 1 - row : row) * w * 4;
            uint8_t *rgb = rows + (size_t)k * w * 3;
            uint8_t *ya = yp + (size_t)row * w;
            uint8_t *aa = alpha + (size_t)row * w;
            for (int x = 0; x < w; x++) {
                int r = src[0], g = src[1], b = src[2], a = src[3];
                if (a == 0) {
                    r = g = b = 0;
                    transparent = 1;
                } else if (a != 255) {
                    r = clamp8((r * 255 + a / 2) / a);
                    g = clamp8((g * 255 + a / 2) / a);
                    b = clamp8((b * 255 + a / 2) / a);
                    transparent = 1;
                }
                rgb[0] = (uint8_t)r;
                rgb[1] = (uint8_t)g;
                rgb[2] = (uint8_t)b;
                ya[x] = (uint8_t)(((66 * r + 129 * g + 25 * b + 128) >> 8) + 16);
                aa[x] = (uint8_t)a;
                src += 4;
                rgb += 3;
            }
        }
        const uint8_t *r0 = rows, *r1 = rows + (size_t)w * 3;
        for (int x = 0; x < cw; x++) {
            int r = (r0[0] + r0[3] + r1[0] + r1[3] + 2) >> 2;
            int g = (r0[1] + r0[4] + r1[1] + r1[4] + 2) >> 2;
            int b = (r0[2] + r0[5] + r1[2] + r1[5] + 2) >> 2;
            *up++ = clamp8(((-38 * r - 74 * g + 112 * b + 128) >> 8) + 128);
            *vp++ = clamp8(((112 * r - 94 * g - 18 * b + 128) >> 8) + 128);
            r0 += 6;
            r1 += 6;
        }
    }
    free(rows);

    if (transparent) {
        s->has_alpha = 1;
    } else {
        free(alpha);
        alpha = NULL;
    }
    s->yuv[s->count] = yuv;
    s->alpha[s->count] = alpha;
    return ++s->count;
}

/* ---------------------------------------------------------------- byte buffer */

typedef struct {
    uint8_t *data;
    size_t size, capacity;
    int failed;
} buf_t;

static void buf_free(buf_t *b) {
    free(b->data);
    memset(b, 0, sizeof(*b));
}

static void buf_put(buf_t *b, const void *data, size_t n) {
    if (b->failed) return;
    if (b->size + n > b->capacity) {
        size_t cap = b->capacity ? b->capacity : 256;
        while (cap < b->size + n) cap *= 2;
        uint8_t *d = realloc(b->data, cap);
        if (!d) {
            b->failed = 1;
            return;
        }
        b->data = d;
        b->capacity = cap;
    }
    memcpy(b->data + b->size, data, n);
    b->size += n;
}

static void buf_byte(buf_t *b, uint8_t v) { buf_put(b, &v, 1); }

/* ---------------------------------------------------------------- EBML / WebM */

static void ebml_id(buf_t *b, uint32_t id) {
    uint8_t bytes[4];
    int n = id > 0xFFFFFF ? 4 : id > 0xFFFF ? 3 : id > 0xFF ? 2 : 1;
    for (int i = 0; i < n; i++) bytes[i] = (uint8_t)(id >> (8 * (n - 1 - i)));
    buf_put(b, bytes, n);
}

static void ebml_size(buf_t *b, uint64_t size) {
    int n = 1;
    while (n < 8 && size >= ((1ULL << (7 * n)) - 1)) n++;
    uint8_t bytes[8];
    for (int i = 0; i < n; i++) bytes[i] = (uint8_t)(size >> (8 * (n - 1 - i)));
    bytes[0] |= (uint8_t)(0x80 >> (n - 1));
    buf_put(b, bytes, n);
}

static void ebml_bytes(buf_t *b, uint32_t id, const void *data, size_t n) {
    ebml_id(b, id);
    ebml_size(b, n);
    buf_put(b, data, n);
}

static void ebml_uint(buf_t *b, uint32_t id, uint64_t v) {
    uint8_t bytes[8];
    int n = 1;
    while (n < 8 && (v >> (8 * n)) != 0) n++;
    for (int i = 0; i < n; i++) bytes[i] = (uint8_t)(v >> (8 * (n - 1 - i)));
    ebml_bytes(b, id, bytes, n);
}

static void ebml_sint16(buf_t *b, uint32_t id, int v) {
    uint8_t bytes[2] = {(uint8_t)((v >> 8) & 0xFF), (uint8_t)(v & 0xFF)};
    ebml_bytes(b, id, bytes, 2);
}

static void ebml_double(buf_t *b, uint32_t id, double v) {
    uint64_t bits;
    memcpy(&bits, &v, 8);
    uint8_t bytes[8];
    for (int i = 0; i < 8; i++) bytes[i] = (uint8_t)(bits >> (8 * (7 - i)));
    ebml_bytes(b, id, bytes, 8);
}

static void ebml_string(buf_t *b, uint32_t id, const char *s) { ebml_bytes(b, id, s, strlen(s)); }

/* Writes `child` as a master element into `parent` and releases it. */
static void ebml_master(buf_t *parent, uint32_t id, buf_t *child) {
    if (child->failed) parent->failed = 1;
    ebml_bytes(parent, id, child->data, child->size);
    buf_free(child);
}

typedef struct {
    uint8_t *data;
    size_t size;
    int key;
} packet_t;

static void packets_free(packet_t *p, int count) {
    if (!p) return;
    for (int i = 0; i < count; i++) free(p[i].data);
    free(p);
}

static void block_header(buf_t *b, int timestamp_ms, int flags) {
    buf_byte(b, 0x81); /* track 1 */
    buf_byte(b, (uint8_t)((timestamp_ms >> 8) & 0xFF));
    buf_byte(b, (uint8_t)(timestamp_ms & 0xFF));
    buf_byte(b, (uint8_t)flags);
}

static int frame_time_ms(int index, int fps) { return (int)(((int64_t)index * 1000 + fps / 2) / fps); }

static int write_webm(buf_t *out, int w, int h, int fps, int count, const packet_t *color,
                      const packet_t *alpha) {
    buf_t header = {0};
    ebml_uint(&header, 0x4286, 1);     /* EBMLVersion */
    ebml_uint(&header, 0x42F7, 1);     /* EBMLReadVersion */
    ebml_uint(&header, 0x42F2, 4);     /* EBMLMaxIDLength */
    ebml_uint(&header, 0x42F3, 8);     /* EBMLMaxSizeLength */
    ebml_string(&header, 0x4282, "webm");
    ebml_uint(&header, 0x4287, 4);     /* DocTypeVersion */
    ebml_uint(&header, 0x4285, 2);     /* DocTypeReadVersion */
    ebml_master(out, 0x1A45DFA3, &header);

    buf_t segment = {0};

    buf_t info = {0};
    ebml_uint(&info, 0x2AD7B1, 1000000); /* TimestampScale: 1 ms */
    ebml_double(&info, 0x4489, (double)count * 1000.0 / fps); /* Duration */
    ebml_string(&info, 0x4D80, "stickerenc");
    ebml_string(&info, 0x5741, "stickerenc");
    ebml_master(&segment, 0x1549A966, &info);

    buf_t video = {0};
    ebml_uint(&video, 0xB0, (uint64_t)w);
    ebml_uint(&video, 0xBA, (uint64_t)h);
    if (alpha) ebml_uint(&video, 0x53C0, 1); /* AlphaMode */

    buf_t entry = {0};
    ebml_uint(&entry, 0xD7, 1);   /* TrackNumber */
    ebml_uint(&entry, 0x73C5, 1); /* TrackUID */
    ebml_uint(&entry, 0x9C, 0);   /* FlagLacing */
    ebml_string(&entry, 0x22B59C, "und");
    ebml_string(&entry, 0x86, "V_VP9");
    ebml_uint(&entry, 0x83, 1);   /* TrackType: video */
    ebml_uint(&entry, 0x23E383, (uint64_t)(1000000000.0 / fps + 0.5)); /* DefaultDuration */
    if (alpha) ebml_uint(&entry, 0x55EE, 1); /* MaxBlockAdditionID */
    ebml_master(&entry, 0xE0, &video);

    buf_t tracks = {0};
    ebml_master(&tracks, 0xAE, &entry);
    ebml_master(&segment, 0x1654AE6B, &tracks);

    uint64_t cluster_position = segment.size;

    buf_t cluster = {0};
    ebml_uint(&cluster, 0xE7, 0); /* Timestamp */
    int previous = 0;
    for (int i = 0; i < count; i++) {
        int ts = frame_time_ms(i, fps);
        if (!alpha) {
            buf_t block = {0};
            block_header(&block, ts, color[i].key ? 0x80 : 0x00);
            buf_put(&block, color[i].data, color[i].size);
            ebml_master(&cluster, 0xA3, &block); /* SimpleBlock */
        } else {
            buf_t group = {0};
            buf_t block = {0};
            block_header(&block, ts, 0x00);
            buf_put(&block, color[i].data, color[i].size);
            ebml_master(&group, 0xA1, &block); /* Block */

            buf_t more = {0};
            ebml_uint(&more, 0xEE, 1); /* BlockAddID */
            ebml_bytes(&more, 0xA5, alpha[i].data, alpha[i].size); /* BlockAdditional */
            buf_t additions = {0};
            ebml_master(&additions, 0xA6, &more);     /* BlockMore */
            ebml_master(&group, 0x75A1, &additions);  /* BlockAdditions */

            if (!color[i].key) ebml_sint16(&group, 0xFB, previous - ts); /* ReferenceBlock */
            ebml_master(&cluster, 0xA0, &group); /* BlockGroup */
        }
        previous = ts;
    }
    ebml_master(&segment, 0x1F43B675, &cluster);

    buf_t positions = {0};
    ebml_uint(&positions, 0xF7, 1);                /* CueTrack */
    ebml_uint(&positions, 0xF1, cluster_position); /* CueClusterPosition */
    buf_t point = {0};
    ebml_uint(&point, 0xB3, 0); /* CueTime */
    ebml_master(&point, 0xB7, &positions);
    buf_t cues = {0};
    ebml_master(&cues, 0xBB, &point);
    ebml_master(&segment, 0x1C53BB6B, &cues);

    ebml_master(out, 0x18538067, &segment);
    return out->failed ? SE_ERR_MEMORY : 0;
}

/* ---------------------------------------------------------------- VP9 encoding */

typedef struct {
    const se_store *store;
    const int *order;
    int count;
    int fps;
    int alpha; /* encode the alpha planes instead of the picture */
    uint8_t *scratch;
    se_progress_fn progress;
    void *progress_ctx;
    int progress_base, progress_total;
} job_t;

static void job_image(job_t *job, int index, vpx_image_t *img) {
    const se_store *s = job->store;
    int frame = job->order[index];
    uint8_t *pixels;
    if (!job->alpha) {
        pixels = s->yuv[frame];
    } else {
        /* The alpha plane travels as the luma of a grey picture. */
        size_t luma = (size_t)s->w * s->h;
        if (s->alpha[frame]) memcpy(job->scratch, s->alpha[frame], luma);
        else memset(job->scratch, 255, luma);
        pixels = job->scratch;
    }
    vpx_img_wrap(img, VPX_IMG_FMT_I420, (unsigned)s->w, (unsigned)s->h, 1, pixels);
}

static int job_tick(job_t *job, int done) {
    if (!job->progress) return 1;
    return job->progress(job->progress_ctx, job->progress_base + done, job->progress_total);
}

/* Runs one encoder pass. Collects first-pass stats into `stats` or frames into `packets`. */
static int run_pass(job_t *job, vpx_codec_enc_cfg_t *cfg, int cpu_used, buf_t *stats, packet_t *packets) {
    vpx_codec_ctx_t codec;
    if (vpx_codec_enc_init(&codec, vpx_codec_vp9_cx(), cfg, 0)) {
        LOGE("encoder init failed (pass %d, %ux%u, %u kbps): %s", (int)cfg->g_pass, cfg->g_w, cfg->g_h,
             cfg->rc_target_bitrate, vpx_codec_error(&codec));
        return SE_ERR_GENERIC;
    }

    vpx_codec_control(&codec, VP8E_SET_CPUUSED, cpu_used);
    vpx_codec_control(&codec, VP9E_SET_ROW_MT, 1);
    vpx_codec_control(&codec, VP9E_SET_TILE_COLUMNS, job->store->w >= 512 ? 1 : 0);
    vpx_codec_control(&codec, VP8E_SET_ENABLEAUTOALTREF, cfg->g_lag_in_frames > 0 ? 1 : 0);
    vpx_codec_control(&codec, VP9E_SET_AQ_MODE, 0);

    int result = 0, done = 0;
    for (int i = 0; i <= job->count && result == 0; i++) {
        vpx_image_t img;
        vpx_image_t *input = NULL;
        if (i < job->count) {
            job_image(job, i, &img);
            input = &img;
        }
        if (vpx_codec_encode(&codec, input, i, 1, 0, VPX_DL_GOOD_QUALITY)) {
            const char *detail = vpx_codec_error_detail(&codec);
            LOGE("encode failed at frame %d of %d: %s (%s)", i, job->count, vpx_codec_error(&codec),
                 detail ? detail : "-");
            result = SE_ERR_GENERIC;
            break;
        }
        int flushing = input == NULL, got;
        do {
            got = 0;
            vpx_codec_iter_t iter = NULL;
            const vpx_codec_cx_pkt_t *pkt;
            while ((pkt = vpx_codec_get_cx_data(&codec, &iter)) != NULL) {
                got = 1;
                if (pkt->kind == VPX_CODEC_STATS_PKT && stats) {
                    buf_put(stats, pkt->data.twopass_stats.buf, pkt->data.twopass_stats.sz);
                } else if (pkt->kind == VPX_CODEC_CX_FRAME_PKT && packets) {
                    int index = (int)pkt->data.frame.pts;
                    if (index < 0 || index >= job->count || packets[index].data) continue;
                    packets[index].data = malloc(pkt->data.frame.sz ? pkt->data.frame.sz : 1);
                    if (!packets[index].data) {
                        result = SE_ERR_MEMORY;
                        break;
                    }
                    memcpy(packets[index].data, pkt->data.frame.buf, pkt->data.frame.sz);
                    packets[index].size = pkt->data.frame.sz;
                    packets[index].key = (pkt->data.frame.flags & VPX_FRAME_IS_KEY) != 0;
                    done++;
                }
            }
            /* While flushing, keep asking until the encoder has nothing left. */
            if (flushing && got && result == 0) {
                if (vpx_codec_encode(&codec, NULL, i, 1, 0, VPX_DL_GOOD_QUALITY)) result = SE_ERR_GENERIC;
            }
        } while (flushing && got && result == 0);

        if (result == 0 && !job_tick(job, packets ? done : (i < job->count ? i + 1 : job->count))) {
            result = SE_ERR_CANCELLED;
        }
    }
    vpx_codec_destroy(&codec);

    if (result == 0 && stats && stats->failed) result = SE_ERR_MEMORY;
    if (result == 0 && packets) {
        for (int i = 0; i < job->count; i++) {
            if (!packets[i].data) {
                LOGE("no packet for frame %d of %d", i, job->count);
                return SE_ERR_GENERIC;
            }
        }
    }
    return result;
}

static int base_config(const job_t *job, vpx_codec_enc_cfg_t *cfg, int bitrate, int threads) {
    if (vpx_codec_enc_config_default(vpx_codec_vp9_cx(), cfg, 0)) return SE_ERR_GENERIC;
    cfg->g_w = (unsigned)job->store->w;
    cfg->g_h = (unsigned)job->store->h;
    cfg->g_timebase.num = 1;
    cfg->g_timebase.den = job->fps;
    cfg->g_threads = (unsigned)(threads < 1 ? 1 : threads);
    cfg->g_error_resilient = 0;
    cfg->rc_end_usage = VPX_VBR;
    cfg->rc_target_bitrate = (unsigned)(bitrate / 1000 < 1 ? 1 : bitrate / 1000);
    cfg->rc_min_quantizer = 0;
    cfg->rc_max_quantizer = 63;
    cfg->rc_undershoot_pct = 100;
    cfg->rc_overshoot_pct = 15;
    /* A sticker is a few seconds long: one keyframe at the start is enough. */
    cfg->kf_mode = VPX_KF_AUTO;
    cfg->kf_min_dist = 0;
    cfg->kf_max_dist = 9999;
    return 0;
}

/* Two-pass encode of the picture: slower than one pass, but hits the size budget far better. */
static int encode_color(job_t *job, int bitrate, int cpu_used, int threads, packet_t *packets) {
    vpx_codec_enc_cfg_t cfg;
    int err = base_config(job, &cfg, bitrate, threads);
    if (err) return err;
    cfg.g_lag_in_frames = job->count > 25 ? 25 : (job->count > 1 ? job->count - 1 : 0);

    buf_t stats = {0};
    cfg.g_pass = VPX_RC_FIRST_PASS;
    job->progress_base = 0;
    err = run_pass(job, &cfg, cpu_used, &stats, NULL);
    if (err == 0) {
        cfg.g_pass = VPX_RC_LAST_PASS;
        cfg.rc_twopass_stats_in.buf = stats.data;
        cfg.rc_twopass_stats_in.sz = stats.size;
        job->progress_base = job->count;
        err = run_pass(job, &cfg, cpu_used, NULL, packets);
    }
    buf_free(&stats);
    return err;
}

static int encode_alpha(job_t *job, int bitrate, int cpu_used, int threads, packet_t *packets) {
    vpx_codec_enc_cfg_t cfg;
    int err = base_config(job, &cfg, bitrate, threads);
    if (err) return err;
    cfg.g_pass = VPX_RC_ONE_PASS;
    cfg.g_lag_in_frames = 0;
    /* Alpha frames must stay decodable in step with the picture: no keyframes of its own. */
    cfg.kf_mode = VPX_KF_DISABLED;

    const se_store *s = job->store;
    size_t luma = (size_t)s->w * s->h, chroma = (size_t)s->cw * s->ch;
    job->scratch = malloc(luma + 2 * chroma);
    if (!job->scratch) return SE_ERR_MEMORY;
    memset(job->scratch + luma, 128, 2 * chroma);
    err = run_pass(job, &cfg, cpu_used < 4 ? 4 : cpu_used, NULL, packets);
    free(job->scratch);
    job->scratch = NULL;
    return err;
}

long se_encode(const se_store *store, const int *order, int count, int fps, int bitrate,
               int cpu_used, int threads, const char *path, se_progress_fn progress, void *ctx) {
    if (!store || !order || count <= 0 || fps <= 0 || !path) return SE_ERR_GENERIC;
    for (int i = 0; i < count; i++) {
        if (order[i] < 0 || order[i] >= store->count) return SE_ERR_GENERIC;
    }

    int with_alpha = 0;
    for (int i = 0; i < count && !with_alpha; i++) with_alpha = store->alpha[order[i]] != NULL;

    packet_t *color = calloc((size_t)count, sizeof(*color));
    packet_t *alpha = with_alpha ? calloc((size_t)count, sizeof(*alpha)) : NULL;
    long result = (!color || (with_alpha && !alpha)) ? SE_ERR_MEMORY : 0;

    job_t job = {0};
    job.store = store;
    job.order = order;
    job.count = count;
    job.fps = fps;
    job.progress = progress;
    job.progress_ctx = ctx;
    /* Progress units: first pass + second pass of the picture, plus the alpha pass. */
    job.progress_total = count * (with_alpha ? 3 : 2);

    int color_bitrate = bitrate;
    if (result == 0 && with_alpha) {
        job.alpha = 1;
        job.progress_base = count * 2;
        result = encode_alpha(&job, bitrate / 5, cpu_used, threads, alpha);
        job.alpha = 0;
        if (result == 0) {
            /* The picture gets whatever the alpha channel did not actually use. */
            size_t bytes = 0;
            for (int i = 0; i < count; i++) bytes += alpha[i].size;
            int used = (int)((double)bytes * 8.0 * fps / count);
            color_bitrate = bitrate - used;
            if (color_bitrate < bitrate / 2) color_bitrate = bitrate / 2;
        }
    }
    if (result == 0) result = encode_color(&job, color_bitrate, cpu_used, threads, color);

    buf_t file = {0};
    if (result == 0) result = write_webm(&file, store->w, store->h, fps, count, color, alpha);
    if (result == 0) {
        FILE *f = fopen(path, "wb");
        if (!f || fwrite(file.data, 1, file.size, f) != file.size) result = SE_ERR_IO;
        if (f && fclose(f) != 0) result = SE_ERR_IO;
        if (result == 0) result = (long)file.size;
    }

    buf_free(&file);
    packets_free(color, count);
    packets_free(alpha, count);
    return result;
}
