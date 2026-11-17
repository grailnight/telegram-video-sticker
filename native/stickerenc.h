/*
 * VP9 (libvpx) encoder with optional alpha channel and a minimal WebM writer,
 * tailored to Telegram video stickers. No Android dependencies: also builds on a desktop for testing.
 */
#ifndef STICKERENC_H
#define STICKERENC_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct se_store se_store;

/* Called after every encoded frame; return 0 to cancel. */
typedef int (*se_progress_fn)(void *ctx, int done, int total);

#define SE_ERR_GENERIC (-1)
#define SE_ERR_CANCELLED (-2)
#define SE_ERR_IO (-3)
#define SE_ERR_MEMORY (-4)

/* Width and height must be even. */
se_store *se_create(int width, int height);
void se_destroy(se_store *store);

/*
 * Appends a frame given as premultiplied RGBA, 4 bytes per pixel, rows of `width` pixels.
 * `bottom_up` is non-zero when the first row is the bottom one (glReadPixels).
 * Returns the new frame count, or a negative error.
 */
int se_add_rgba(se_store *store, const uint8_t *rgba, int bottom_up);

int se_frame_count(const se_store *store);
/* Non-zero once any stored frame had a non-opaque pixel; such stickers get an alpha channel. */
int se_has_alpha(const se_store *store);

/*
 * Encodes the frames listed in `order` (indices into the store, repeats allowed) at a constant
 * `fps` and writes a WebM file. `bitrate` is the total target in bits per second.
 * Returns the file size in bytes, or a negative error.
 */
long se_encode(const se_store *store, const int *order, int count, int fps, int bitrate,
               int cpu_used, int threads, const char *path, se_progress_fn progress, void *ctx);

#ifdef __cplusplus
}
#endif

#endif
