#include "bzlib.h"
#include <stdio.h>
int toto_bunzip2(const char *in_path, const char *out_path) {
    FILE *in = fopen(in_path, "rb");
    if (!in) return -1;
    FILE *out = fopen(out_path, "wb");
    if (!out) { fclose(in); return -2; }
    BZFILE *bz = NULL;
    int bzerror = BZ_OK;
    bz = BZ2_bzReadOpen(&bzerror, in, 0, 0, NULL, 0);
    if (bzerror != BZ_OK) {
        if (bz) BZ2_bzReadClose(&bzerror, bz);
        fclose(in); fclose(out);
        return -3;
    }
    char buf[1 << 16];
    while (bzerror == BZ_OK) {
        int n = BZ2_bzRead(&bzerror, bz, buf, (int)sizeof(buf));
        if (n > 0) {
            if (fwrite(buf, 1, (size_t)n, out) != (size_t)n) {
                BZ2_bzReadClose(&bzerror, bz);
                fclose(in); fclose(out);
                return -4;
            }
        }
    }
    int rc = (bzerror == BZ_STREAM_END) ? 0 : -5;
    BZ2_bzReadClose(&bzerror, bz);
    fclose(in);
    fclose(out);
    return rc;
}
