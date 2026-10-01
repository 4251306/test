#ifndef P16_MIX_H
#define P16_MIX_H

#include <stdint.h>

#define P16_CHANNELS 16
#define P16_GAIN_FULL 1024
#define P16_VOL_FULL 1000

typedef struct {
    int32_t master;
    int32_t gain_l[P16_CHANNELS];
    int32_t gain_r[P16_CHANNELS];
} P16Mix;

void p16_mix_frame(const P16Mix *mix, const int32_t *samples, int32_t *left, int32_t *right);

#endif
