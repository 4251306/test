#include "protocol.h"

#include <stdlib.h>
#include <string.h>

static int clamp_gain(long value) {
    if (value < 0 || value > P16_GAIN_FULL) {
        return -1;
    }
    return (int)value;
}

int p16_parse_mix(const char *line, P16Mix *mix) {
    if (strncmp(line, "MIX ", 4) != 0) {
        return 0;
    }
    char *end = NULL;
    long master = strtol(line + 4, &end, 10);
    if (end == line + 4 || master < 0 || master > P16_VOL_FULL) {
        return 0;
    }
    const char *cursor = end;
    int32_t left[P16_CHANNELS];
    int32_t right[P16_CHANNELS];
    for (int i = 0; i < P16_CHANNELS; i++) {
        while (*cursor == ' ') {
            cursor++;
        }
        long gain_l = strtol(cursor, &end, 10);
        if (end == cursor || *end != ',') {
            return 0;
        }
        cursor = end + 1;
        long gain_r = strtol(cursor, &end, 10);
        if (end == cursor) {
            return 0;
        }
        int l = clamp_gain(gain_l);
        int r = clamp_gain(gain_r);
        if (l < 0 || r < 0) {
            return 0;
        }
        left[i] = l;
        right[i] = r;
        cursor = end;
    }
    while (*cursor == ' ' || *cursor == '\r') {
        cursor++;
    }
    if (*cursor != '\0') {
        return 0;
    }
    mix->master = (int32_t)master;
    for (int i = 0; i < P16_CHANNELS; i++) {
        mix->gain_l[i] = left[i];
        mix->gain_r[i] = right[i];
    }
    return 1;
}
