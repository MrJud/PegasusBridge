#ifndef PB_PATCHLEVEL_H
#define PB_PATCHLEVEL_H

/*
 * How many local patches the rcheevos in rcheevos/ carries on top of the
 * upstream release it was taken from. rcheevos-patches/PATCHES.md lists them,
 * and whoever adds one there adds one here.
 *
 * It is a number of its own, beside the upstream version, because two builds
 * of one upstream version that differ in a patch do not answer alike: a file
 * one of them refuses, the other gives a hash.
 */
#define PB_RCHEEVOS_PATCHLEVEL 6

#endif /* PB_PATCHLEVEL_H */
