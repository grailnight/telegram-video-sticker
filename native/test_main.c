/* Desktop smoke test: encodes a synthetic animation, optionally with a circular alpha mask. */
#include <math.h>
#include <stdio.h>
#include <stdlib.h>

#include "stickerenc.h"

int main(int argc, char **argv) {
    if (argc < 7) {
        fprintf(stderr, "usage: %s out.webm width height frames bitrate alpha(0|1) [boomerang(0|1)] [cpu_used]\n", argv[0]);
        return 2;
    }
    int w = atoi(argv[2]), h = atoi(argv[3]), n = atoi(argv[4]), bitrate = atoi(argv[5]), with_alpha = atoi(argv[6]);
    se_store *store = se_create(w, h);
    unsigned char *rgba = malloc((size_t)w * h * 4);
    int *order = malloc(sizeof(int) * n);
    for (int f = 0; f < n; f++) {
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                double dx = x - w / 2.0, dy = y - h / 2.0;
                double d = sqrt(dx * dx + dy * dy) / (w < h ? w : h) * 2.0;
                int a = !with_alpha ? 255 : d < 0.95 ? 255 : d > 1.0 ? 0 : (int)((1.0 - d) / 0.05 * 255);
                int r = (x * 255 / w + f * 4) & 255, g = (y * 255 / h) & 255, b = ((x + y) / 4 + f * 8) & 255;
                if (y < h / 8) { r = 255; g = 0; b = 0; } /* red stripe marks the top */
                unsigned char *p = rgba + ((size_t)y * w + x) * 4;
                p[0] = (unsigned char)(r * a / 255);
                p[1] = (unsigned char)(g * a / 255);
                p[2] = (unsigned char)(b * a / 255);
                p[3] = (unsigned char)a;
            }
        }
        se_add_rgba(store, rgba, 0);
        order[f] = f;
    }
    int count = n;
    if (argc > 7 && atoi(argv[7]) && n >= 3) { /* boomerang */
        count = 2 * n - 2;
        order = realloc(order, sizeof(int) * count);
        for (int i = 0; i < count; i++) order[i] = i < n ? i : 2 * n - 2 - i;
    }
    int cpu = argc > 8 ? atoi(argv[8]) : 2;
    long size = se_encode(store, order, count, 30, bitrate, cpu, 4, argv[1], NULL, NULL);
    printf("frames=%d alpha=%d size=%ld\n", se_frame_count(store), se_has_alpha(store), size);
    se_destroy(store);
    return size > 0 ? 0 : 1;
}
