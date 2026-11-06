#ifndef TUPREL_MIGRATION_PUBLISHER_PROTOCOL_H
#define TUPREL_MIGRATION_PUBLISHER_PROTOCOL_H

#include <stdio.h>
#include <stddef.h>

enum result { MOVED = 0, REFUSED = 10, COLLISION = 11, UNKNOWN = 12 };

static int finish(enum result result) {
    const char *messages[] = {"MOVED", "REFUSED", "COLLISION", "UNKNOWN"};
    unsigned int index = result == MOVED ? 0U : (unsigned int)result - 9U;
    if (puts(messages[index]) == EOF || fflush(stdout) == EOF) {
        return result == MOVED ? UNKNOWN : result;
    }
    return result;
}

static int proceed(void) {
    return fflush(stdout) == 0 && getchar() == 'G' && getchar() == 'O'
        && getchar() == '\n' && getchar() == EOF && feof(stdin) && !ferror(stdin);
}

/* Names are ASCII protocol values on both platforms. */
static int digit(int c) { return c >= '0' && c <= '9'; }
static int lower(int c) { return c >= 'a' && c <= 'z'; }

static int stage_name(const char *name, size_t length) {
    const char prefix[] = ".migration-stage-";
    if (length != sizeof prefix - 1 + 32) { return 0; }
    for (size_t i = 0; i < sizeof prefix - 1; i++) {
        if (name[i] != prefix[i]) { return 0; }
    }
    for (size_t i = sizeof prefix - 1; i < length; i++) {
        if (!digit(name[i]) && !(name[i] >= 'a' && name[i] <= 'f')) { return 0; }
    }
    return 1;
}

static int destination_name(const char *name, size_t length) {
    if (length < 17 || length > 80 || name[8] != '_' || name[15] != '_' || !lower(name[16])) { return 0; }
    for (size_t i = 0; i < 15; i++) {
        if (i != 8 && !digit(name[i])) { return 0; }
    }
    for (size_t i = 17; i < length; i++) {
        if (lower(name[i]) || digit(name[i])) { continue; }
        if ((name[i] != '-' && name[i] != '_') || i + 1 == length
                || (!lower(name[i + 1]) && !digit(name[i + 1]))) { return 0; }
    }
    return 1;
}

#endif
