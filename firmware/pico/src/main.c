#include "pico/stdlib.h"

#include "mix.h"
#include "protocol.h"

#include <stdio.h>
#include <string.h>

/*
 * USB serial control for the Pi mixer page.
 *
 * Samples are filled in by an I2S reader on the AES receiver chips.
 * p16_samples[n] is the latest signed 22-bit sample for channel n.
 * This loop applies the mix the Pi sent. Wiring is in docs/wiring.md.
 */
static volatile int32_t p16_samples[P16_CHANNELS];
static P16Mix p16_mix;

int main(void) {
    stdio_init_all();
    char line[384];
    int length = 0;

    while (1) {
        int ch = getchar_timeout_us(1000);
        if (ch == PICO_ERROR_TIMEOUT) {
            continue;
        }
        if (ch == '\r') {
            continue;
        }
        if (ch != '\n') {
            if (length < (int)sizeof(line) - 1) {
                line[length++] = (char)ch;
            }
            continue;
        }
        line[length] = '\0';
        length = 0;
        if (line[0] == '\0') {
            continue;
        }
        if (strcmp(line, "PING") == 0) {
            printf("PONG\n");
            continue;
        }
        if (p16_parse_mix(line, &p16_mix)) {
            int32_t left = 0;
            int32_t right = 0;
            int32_t snapshot[P16_CHANNELS];
            for (int i = 0; i < P16_CHANNELS; i++) {
                snapshot[i] = p16_samples[i];
            }
            p16_mix_frame(&p16_mix, snapshot, &left, &right);
            printf("OK %ld %ld\n", (long)left, (long)right);
            continue;
        }
        printf("ERR\n");
    }
}
