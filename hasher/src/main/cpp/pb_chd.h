/*
 * pb_chd.h — a disc kept in a .chd, read for rcheevos.
 *
 * rcheevos hashes a disc through a reader of tracks and sectors, and the one
 * it has of its own reads a cue sheet, a .gdi sheet or a plain image. A .chd
 * is the same disc packed hunk by hunk, with the tracks described in text
 * beside them. pb_chd.c reads one through libchdr and hands rcheevos the
 * sectors it asks for, so that every disc console is hashed by rcheevos' own
 * code, from a .chd as from a cue sheet.
 */

#ifndef PB_CHD_H
#define PB_CHD_H

#include "rc_hash.h"

/*
 * Counted up whenever the reader comes to answer otherwise for some file.
 * The library says it in its version, and a scan keeps the verdict of a
 * .chd under it.
 */
#define PB_CHD_READER 2

/*
 * Puts the reader on an iterator made with rc_hash_initialize_iterator, and
 * is to be called after it, as the callback for errors is set after it. From
 * then on a track of a path that ends in .chd is opened here, and a track of
 * any other path by rcheevos as before. An iterator whose reader of tracks is
 * no longer rcheevos' own is left alone: whoever changed it reads the tracks.
 */
void pb_chd_install(rc_hash_iterator_t* iterator);

/*
 * Whether this is what the reader says when a hunk of a .chd could not be
 * read: the file is cut short there, or what it holds does not unpack to the
 * checksum its map states. rcheevos takes a sector that could not be read
 * for the end of the file it is hashing, and would answer the hash of what
 * came before it. So whoever keeps the reasons of a call looks at each with
 * this, and takes a call in which it was said for one that gave no hash.
 */
int pb_chd_is_damage(const char* message);

#endif /* PB_CHD_H */
