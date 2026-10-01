#ifndef P16_PROTOCOL_H
#define P16_PROTOCOL_H

#include "mix.h"

/* Parse "MIX <master> <l>,<r> ..." into mix. Returns 1 on success. */
int p16_parse_mix(const char *line, P16Mix *mix);

#endif
