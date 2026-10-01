#include "ultranet.h"

int p16_demux_word(int stream, int is_left, uint32_t word24, int32_t *sample) {
    if (stream != 0 && stream != 1) {
        return -1;
    }
    word24 &= 0xFFFFFFu;
    unsigned pair = word24 & 0x3u;
    int32_t audio = (int32_t)((word24 >> 2) & 0x3FFFFFu);
    if (audio & (1 << 21)) {
        audio -= (1 << 22);
    }
    *sample = audio;
    return stream * 8 + (int)pair * 2 + (is_left ? 0 : 1);
}
