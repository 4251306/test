#include "mix.h"

static int32_t to_i16(int64_t value) {
    if (value >= 0) {
        value = value / 64;
    } else {
        value = -((-value) / 64);
    }
    if (value > 32767) {
        return 32767;
    }
    if (value < -32768) {
        return -32768;
    }
    return (int32_t)value;
}

void p16_mix_frame(const P16Mix *mix, const int32_t *samples, int32_t *left, int32_t *right) {
    int64_t acc_l = 0;
    int64_t acc_r = 0;
    int32_t master = mix->master;
    if (master < 0) {
        master = 0;
    }
    if (master > P16_VOL_FULL) {
        master = P16_VOL_FULL;
    }
    for (int i = 0; i < P16_CHANNELS; i++) {
        acc_l += (int64_t)samples[i] * mix->gain_l[i];
        acc_r += (int64_t)samples[i] * mix->gain_r[i];
    }
    acc_l = (acc_l * master) / (P16_GAIN_FULL * P16_VOL_FULL);
    acc_r = (acc_r * master) / (P16_GAIN_FULL * P16_VOL_FULL);
    *left = to_i16(acc_l);
    *right = to_i16(acc_r);
}
