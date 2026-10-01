#include "mix.h"
#include "protocol.h"
#include "ultranet.h"

#include <stdio.h>
#include <string.h>

static int fail(const char *message) {
    fprintf(stderr, "FAIL %s\n", message);
    return 1;
}

int main(void) {
    const char *line =
        "MIX 1000 1024,0 0,0 0,0 0,0 0,0 0,0 0,0 0,0 0,0 0,0 0,0 0,0 0,0 0,0 0,0 0,0";
    P16Mix mix;
    memset(&mix, 0, sizeof(mix));
    if (!p16_parse_mix(line, &mix)) {
        return fail("parse");
    }
    if (mix.master != 1000 || mix.gain_l[0] != 1024 || mix.gain_r[0] != 0) {
        return fail("parsed values");
    }
    int32_t samples[16] = {0};
    samples[0] = 64 * 32767;
    int32_t left = 0;
    int32_t right = 1;
    p16_mix_frame(&mix, samples, &left, &right);
    if (left != 32767 || right != 0) {
        fprintf(stderr, "got %d %d\n", left, right);
        return fail("full scale left");
    }
    samples[0] = 64 * -32768;
    p16_mix_frame(&mix, samples, &left, &right);
    if (left != -32768 || right != 0) {
        return fail("full scale negative");
    }
    if (p16_parse_mix("MIX 10 1,2", &mix)) {
        return fail("short line should fail");
    }

    int32_t sample = 0;
    int channel = p16_demux_word(0, 1, (uint32_t)((1000u << 2) | 0u), &sample);
    if (channel != 0 || sample != 1000) {
        return fail("demux ch 1");
    }
    channel = p16_demux_word(1, 0, (uint32_t)((5u << 2) | 3u), &sample);
    if (channel != 15 || sample != 5) {
        return fail("demux ch 16");
    }
    uint32_t negative = ((uint32_t)(1u << 21) << 2) | 1u;
    channel = p16_demux_word(0, 0, negative, &sample);
    if (channel != 3 || sample != -(1 << 21)) {
        fprintf(stderr, "channel %d sample %d\n", channel, sample);
        return fail("sign extend");
    }
    printf("ok\n");
    return 0;
}
