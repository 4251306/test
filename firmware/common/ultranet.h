#ifndef P16_ULTRANET_H
#define P16_ULTRANET_H

#include <stdint.h>

/* 24-bit AES word from an AK4114/DIX9211. Low 2 bits are the pair index.
   Returns the 0-based channel, and writes a signed 22-bit sample. */
int p16_demux_word(int stream, int is_left, uint32_t word24, int32_t *sample);

#endif
