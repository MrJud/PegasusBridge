/*
 * rhash_tests.c — rcheevos' own tests of its hashing, and no other of its
 * tests.
 *
 *     rhash_tests
 *
 * The copy of rcheevos in hasher/src/main/cpp/rcheevos carries its tests, and
 * the main() they come with, test/test.c, runs all of them: the ones of the
 * achievement runtime, of the client and of the web API beside the ones of
 * the hashing. The library is made of the hashing alone, and one file the
 * rest needs to link, src/rapi/rc_api_editor.c, is left out of the copy on
 * purpose. So this is that main() cut down to the five suites under
 * test/rhash, in the order upstream runs them, and it ends as upstream's
 * does: a last line "Done. N/N passed in S seconds", and 0 when none failed.
 *
 * The rows of native_repro_test.py say that a patch of ours refuses what it
 * is there to refuse. These say the other half in upstream's own words: that
 * the files rcheevos' authors hash still hash to what they expect, on the
 * sources as the patches left them.
 *
 * tests/rcheevos_rhash_test.sh compiles and runs it. It is not built by
 * native/build.sh and is no part of the library.
 */
#include "test_framework.h"

extern void test_hash(void);
extern void test_hash_rom(void);
extern void test_cdreader(void);
extern void test_hash_disc(void);
extern void test_hash_zip(void);

TEST_FRAMEWORK_DECLARATIONS()

int main(void) {
  TEST_FRAMEWORK_INIT();

  test_hash();
  test_hash_rom();
  test_cdreader();
  test_hash_disc();
  test_hash_zip();

  TEST_FRAMEWORK_SHUTDOWN();

  return TEST_FRAMEWORK_PASSED() ? 0 : 1;
}
