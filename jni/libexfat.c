/******************************************************************************
 * libexfat.c
 *
 * Minimal exFAT formatter core for MultiBooter.
 *
 * Copyright (c) 2026, werismoln <vlkanblek@gmail.com>
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License as
 * published by the Free Software Foundation; either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * General Public License for more details.
 *
 * Purpose
 * -------
 * This file implements the on-disk structures required to FORMAT an empty
 * exFAT 1.00 volume.  It intentionally does NOT implement ordinary file
 * creation, directory traversal, or file I/O.  Those operations can be left
 * to the operating system after the newly-created volume has been mounted.
 *
 * Implemented formatter structures:
 *
 *   - Main Boot Region
 *   - Backup Boot Region
 *   - Extended Boot Sectors
 *   - OEM Parameters sector (Null Parameters)
 *   - Reserved Boot sector
 *   - Main / Backup Boot Checksum sectors
 *   - First FAT
 *   - Allocation Bitmap
 *   - Full-range uncompressed Up-case Table
 *   - Root Directory
 *
 * The formatter uses one FAT (NumberOfFats = 1), which is a valid exFAT
 * configuration.
 *
 * The Up-case Table is intentionally generated rather than stored as a large
 * binary blob.  It covers the complete UTF-16 code-unit range 0000h..FFFFh.
 * The mandatory ASCII lowercase mappings a..z map to A..Z; all other code
 * units use identity mappings.  The exFAT specification permits a formatter
 * to define its own complete Up-case Table.  Its checksum is calculated and
 * stored in the root directory entry.
 *
 * IMPORTANT
 * ---------
 * The write callback operates in MEDIA-RELATIVE sectors.
 *
 *   physical LBA = options.partition_offset + volume-relative sector
 *
 * Therefore:
 *
 *   options.partition_offset = starting LBA of the partition
 *   options.volume_length    = partition length in sectors
 *
 ******************************************************************************/

#include <stdint.h>
#include <stddef.h>
#include <stdlib.h>
#include <string.h>
#include <limits.h>

/* ------------------------------------------------------------------------- */
/* PUBLIC BASIC TYPES                                                        */
/* ------------------------------------------------------------------------- */

typedef uint8_t  UCHAR;
typedef uint16_t USHORT;
typedef uint32_t UInt32;
typedef uint16_t UInt16;
typedef uint64_t UInt64;
typedef uint16_t WCHAR;

/* ------------------------------------------------------------------------- */
/* CONSTANTS                                                                 */
/* ------------------------------------------------------------------------- */

#define EXFAT_REVISION_1_00             0x0100u

#define EXFAT_BOOT_REGION_SECTORS       12u
#define EXFAT_MAIN_BOOT_START           0u
#define EXFAT_BACKUP_BOOT_START         12u
#define EXFAT_MIN_FAT_OFFSET            24u

#define EXFAT_BOOT_SIGNATURE            0xAA55u
#define EXFAT_EXTENDED_BOOT_SIGNATURE   0xAA550000u

#define EXFAT_FAT_MEDIA_ENTRY           0xFFFFFFF8u
#define EXFAT_FAT_EOF                   0xFFFFFFFFu
#define EXFAT_MAX_CLUSTER_COUNT         0xFFFFFFF5u /* 2^32 - 11 */

#define EXFAT_ENTRY_ALLOCATION_BITMAP   0x81u
#define EXFAT_ENTRY_UPCASE_TABLE        0x82u

#define EXFAT_UPCASE_CODE_UNITS         65536u
#define EXFAT_UPCASE_DATA_LENGTH        ((uint64_t)EXFAT_UPCASE_CODE_UNITS * 2u)

#define EXFAT_DEFAULT_BYTES_SHIFT       9u          /* 512 bytes */
#define EXFAT_AUTO_CLUSTER_SHIFT        0xFFu
#define EXFAT_TARGET_CLUSTER_BYTES      (128u * 1024u)

#define EXFAT_DEFAULT_DRIVE_SELECT      0x80u

/* ------------------------------------------------------------------------- */
/* RETURN CODES                                                              */
/* ------------------------------------------------------------------------- */

enum {
    EXFAT_OK = 0,
    EXFAT_ERROR_ARGUMENT = -1,
    EXFAT_ERROR_GEOMETRY = -2,
    EXFAT_ERROR_MEMORY = -3,
    EXFAT_ERROR_IO = -4,
    EXFAT_ERROR_OVERFLOW = -5
};

/* ------------------------------------------------------------------------- */
/* ENDIAN HELPERS                                                            */
/* ------------------------------------------------------------------------- */

static uint16_t exfat_bswap16(uint16_t v)
{
    return (uint16_t)((v >> 8) | (v << 8));
}

static uint32_t exfat_bswap32(uint32_t v)
{
    return ((v & 0x000000FFu) << 24) |
           ((v & 0x0000FF00u) << 8)  |
           ((v & 0x00FF0000u) >> 8)  |
           ((v & 0xFF000000u) >> 24);
}

static uint64_t exfat_bswap64(uint64_t v)
{
    return ((uint64_t)exfat_bswap32((uint32_t)v) << 32) |
           (uint64_t)exfat_bswap32((uint32_t)(v >> 32));
}

static int exfat_host_is_little_endian(void)
{
    const uint16_t x = 1;
    return *((const uint8_t *)&x) == 1;
}

static uint16_t exfat_cpu_to_le16(uint16_t v)
{
    return exfat_host_is_little_endian() ? v : exfat_bswap16(v);
}

static uint32_t exfat_cpu_to_le32(uint32_t v)
{
    return exfat_host_is_little_endian() ? v : exfat_bswap32(v);
}

static uint64_t exfat_cpu_to_le64(uint64_t v)
{
    return exfat_host_is_little_endian() ? v : exfat_bswap64(v);
}

/* ------------------------------------------------------------------------- */
/* ON-DISK STRUCTURES                                                        */
/* ------------------------------------------------------------------------- */

#pragma pack(push, 1)

/*
 * Main and Backup Boot Sector.
 *
 * Bytes 0..511 are defined by the exFAT specification.  On sector sizes
 * larger than 512 bytes, bytes after this structure are excess space.
 */
typedef struct {
    uint8_t  JumpBoot[3];
    uint8_t  FileSystemName[8];
    uint8_t  MustBeZero[53];

    uint64_t PartitionOffset;
    uint64_t VolumeLength;

    uint32_t FatOffset;
    uint32_t FatLength;
    uint32_t ClusterHeapOffset;
    uint32_t ClusterCount;
    uint32_t FirstClusterOfRootDirectory;
    uint32_t VolumeSerialNumber;

    uint16_t FileSystemRevision;
    uint16_t VolumeFlags;

    uint8_t  BytesPerSectorShift;
    uint8_t  SectorsPerClusterShift;
    uint8_t  NumberOfFats;
    uint8_t  DriveSelect;
    uint8_t  PercentInUse;

    uint8_t  Reserved[7];
    uint8_t  BootCode[390];

    uint16_t BootSignature;
} exFAT_BootSector;

/* Generic Directory Entry Template, exactly 32 bytes. */
typedef struct {
    uint8_t  EntryType;
    uint8_t  CustomDefined[19];
    uint32_t FirstCluster;
    uint64_t DataLength;
} GenericDirectoryEntry;

/* Allocation Bitmap Directory Entry, exactly 32 bytes. */
typedef struct {
    uint8_t  EntryType;
    uint8_t  BitmapFlags;
    uint8_t  Reserved[18];
    uint32_t FirstCluster;
    uint64_t DataLength;
} exFAT_AllocationBitmapEntry;

/* Up-case Table Directory Entry, exactly 32 bytes. */
typedef struct {
    uint8_t  EntryType;
    uint8_t  Reserved1[3];
    uint32_t TableChecksum;
    uint8_t  Reserved2[12];
    uint32_t FirstCluster;
    uint64_t DataLength;
} exFAT_UpcaseTableEntry;

#pragma pack(pop)

#if defined(__STDC_VERSION__) && (__STDC_VERSION__ >= 201112L)
_Static_assert(sizeof(exFAT_BootSector) == 512, "exFAT_BootSector must be 512 bytes");
_Static_assert(sizeof(GenericDirectoryEntry) == 32, "GenericDirectoryEntry must be 32 bytes");
_Static_assert(sizeof(exFAT_AllocationBitmapEntry) == 32, "AllocationBitmapEntry must be 32 bytes");
_Static_assert(sizeof(exFAT_UpcaseTableEntry) == 32, "UpcaseTableEntry must be 32 bytes");
#endif

/* ------------------------------------------------------------------------- */
/* I/O ABSTRACTION                                                           */
/* ------------------------------------------------------------------------- */

/*
 * write_sectors()
 *
 * Return 0 on success, non-zero on failure.
 *
 * lba is MEDIA-RELATIVE.
 * sector_count is always >= 1.
 * data contains sector_count complete sectors.
 */
typedef int (*exfat_write_sectors_fn)(
    void *context,
    uint64_t lba,
    const void *data,
    uint32_t sector_count
);

/*
 * flush()
 *
 * Optional.  Return 0 on success, non-zero on failure.
 */
typedef int (*exfat_flush_fn)(void *context);

typedef struct {
    void *context;
    exfat_write_sectors_fn write_sectors;
    exfat_flush_fn flush;
} exfat_io;

/* ------------------------------------------------------------------------- */
/* FORMAT OPTIONS / RESULT                                                   */
/* ------------------------------------------------------------------------- */

typedef struct {
    /*
     * Media-relative starting LBA of the exFAT partition.
     *
     * 0 is valid.  Per the specification, PartitionOffset == 0 means
     * implementations shall ignore this field.
     */
    uint64_t partition_offset;

    /*
     * Length of the exFAT volume in sectors.
     */
    uint64_t volume_length;

    /*
     * Volume serial written to the Boot Sector.
     */
    uint32_t volume_serial;

    /*
     * Valid values: 9..12.
     *
     * 0 means use the formatter default (9 = 512-byte sectors).
     */
    uint8_t bytes_per_sector_shift;

    /*
     * Valid values:
     *
     *   0 .. (25 - BytesPerSectorShift)
     *
     * EXFAT_AUTO_CLUSTER_SHIFT selects a formatter policy which begins with
     * approximately 128 KiB clusters and increases the cluster size only if
     * required by the exFAT cluster-count limit.
     */
    uint8_t sectors_per_cluster_shift;

    /*
     * Extended INT 13h drive number.
     *
     * All byte values are valid according to the specification.
     * exfat_format_options_init() defaults this field to 80h.
     */
    uint8_t drive_select;
} exfat_format_options;

typedef struct {
    uint32_t bytes_per_sector;
    uint32_t sectors_per_cluster;
    uint64_t bytes_per_cluster;

    uint32_t fat_offset;
    uint32_t fat_length;

    uint32_t cluster_heap_offset;
    uint32_t cluster_count;

    uint32_t bitmap_first_cluster;
    uint32_t bitmap_cluster_count;
    uint64_t bitmap_data_length;

    uint32_t upcase_first_cluster;
    uint32_t upcase_cluster_count;
    uint64_t upcase_data_length;
    uint32_t upcase_checksum;

    uint32_t root_first_cluster;
    uint32_t root_cluster_count;

    uint32_t allocated_cluster_count;
    uint8_t percent_in_use;
} exfat_geometry;

/* ------------------------------------------------------------------------- */
/* SMALL ARITHMETIC HELPERS                                                  */
/* ------------------------------------------------------------------------- */

static uint64_t exfat_div_ceil_u64(uint64_t value, uint64_t divisor)
{
    if (divisor == 0) {
        return 0;
    }

    return value / divisor + ((value % divisor) != 0 ? 1u : 0u);
}

static int exfat_add_u64_overflow(uint64_t a, uint64_t b, uint64_t *out)
{
    if (UINT64_MAX - a < b) {
        return 1;
    }

    *out = a + b;
    return 0;
}

/* ------------------------------------------------------------------------- */
/* SPECIFICATION CHECKSUM / HASH ALGORITHMS                                  */
/* ------------------------------------------------------------------------- */

/*
 * Boot Checksum Computation.
 *
 * Sectors points to an in-memory copy of the FIRST 11 sectors in a Boot
 * Region.  VolumeFlags bytes 106 and 107, and PercentInUse byte 112, are
 * excluded.
 */
UInt32 BootChecksum(UCHAR *Sectors, USHORT BytesPerSector)
{
    UInt32 NumberOfBytes = (UInt32)BytesPerSector * 11u;
    UInt32 Checksum = 0;
    UInt32 Index;

    for (Index = 0; Index < NumberOfBytes; Index++) {

        if ((Index == 106u) ||
            (Index == 107u) ||
            (Index == 112u)) {
            continue;
        }

        Checksum =
            ((Checksum & 1u) ? 0x80000000u : 0u) +
            (Checksum >> 1) +
            (UInt32)Sectors[Index];
    }

    return Checksum;
}

/*
 * Directory Entry Set Checksum Computation.
 *
 * Bytes 2 and 3 of the primary entry are the SetChecksum field and are
 * excluded.
 */
UInt16 EntrySetChecksum(UCHAR *Entries, UCHAR SecondaryCount)
{
    UInt16 NumberOfBytes =
        (UInt16)(((UInt16)SecondaryCount + 1u) * 32u);

    UInt16 Checksum = 0;
    UInt16 Index;

    for (Index = 0; Index < NumberOfBytes; Index++) {

        if ((Index == 2u) || (Index == 3u)) {
            continue;
        }

        Checksum =
            (UInt16)(
                ((Checksum & 1u) ? 0x8000u : 0u) +
                (Checksum >> 1) +
                (UInt16)Entries[Index]
            );
    }

    return Checksum;
}

/* Up-case Table Checksum Computation. */
UInt32 TableChecksum(UCHAR *Table, UInt64 DataLength)
{
    UInt32 Checksum = 0;
    UInt64 Index;

    for (Index = 0; Index < DataLength; Index++) {

        Checksum =
            ((Checksum & 1u) ? 0x80000000u : 0u) +
            (Checksum >> 1) +
            (UInt32)Table[Index];
    }

    return Checksum;
}

/*
 * File Name Hash Computation.
 *
 * The caller must pass the UP-CASED UTF-16 file name, as required by exFAT.
 */
UInt16 NameHash(WCHAR *FileName, UCHAR NameLength)
{
    UCHAR *Buffer = (UCHAR *)FileName;
    UInt16 NumberOfBytes = (UInt16)NameLength * 2u;
    UInt16 Hash = 0;
    UInt16 Index;

    for (Index = 0; Index < NumberOfBytes; Index++) {

        Hash =
            (UInt16)(
                ((Hash & 1u) ? 0x8000u : 0u) +
                (Hash >> 1) +
                (UInt16)Buffer[Index]
            );
    }

    return Hash;
}

/* ------------------------------------------------------------------------- */
/* UP-CASE TABLE                                                             */
/* ------------------------------------------------------------------------- */

/*
 * Custom complete Up-case Table used by this formatter.
 *
 * The specification allows a formatter to define its own table if it covers
 * the complete Unicode code-unit range 0000h..FFFFh.  The first 128 mappings
 * have mandatory values; for ASCII this means lowercase a..z map to A..Z.
 *
 * This implementation maps:
 *
 *     0061h..007Ah -> 0041h..005Ah
 *
 * and leaves all remaining UTF-16 code units unchanged.
 *
 * This is intentionally UNCOMPRESSED.
 */
static uint16_t exfat_upcase_mapping(uint16_t code_unit)
{
    if (code_unit >= 0x0061u && code_unit <= 0x007Au) {
        return (uint16_t)(code_unit - 0x20u);
    }

    return code_unit;
}

static uint32_t exfat_update_checksum32(uint32_t checksum, uint8_t byte)
{
    return
        ((checksum & 1u) ? 0x80000000u : 0u) +
        (checksum >> 1) +
        (uint32_t)byte;
}

static uint32_t exfat_compute_generated_upcase_checksum(void)
{
    uint32_t checksum = 0;
    uint32_t code_unit;

    for (code_unit = 0; code_unit < EXFAT_UPCASE_CODE_UNITS; code_unit++) {

        uint16_t mapped =
            exfat_upcase_mapping((uint16_t)code_unit);

        /*
         * exFAT on-disk structures are little-endian.
         */
        checksum =
            exfat_update_checksum32(
                checksum,
                (uint8_t)(mapped & 0xFFu)
            );

        checksum =
            exfat_update_checksum32(
                checksum,
                (uint8_t)((mapped >> 8) & 0xFFu)
            );
    }

    return checksum;
}

/* ------------------------------------------------------------------------- */
/* FORMAT GEOMETRY                                                           */
/* ------------------------------------------------------------------------- */

static uint8_t exfat_default_cluster_shift(uint8_t bytes_shift)
{
    /*
     * Formatter policy, not a mandatory value from the specification:
     * target approximately 128 KiB per cluster.
     */
    uint8_t shift;

    if (bytes_shift >= 17u) {
        return 0;
    }

    shift = (uint8_t)(17u - bytes_shift); /* 2^17 = 128 KiB */

    if (shift > (uint8_t)(25u - bytes_shift)) {
        shift = (uint8_t)(25u - bytes_shift);
    }

    return shift;
}

static int exfat_calculate_for_cluster_shift(
    const exfat_format_options *options,
    uint8_t bytes_shift,
    uint8_t cluster_shift,
    exfat_geometry *geometry
)
{
    uint64_t bytes_per_sector;
    uint64_t sectors_per_cluster;
    uint64_t bytes_per_cluster;

    uint64_t cluster_count;
    uint64_t new_cluster_count;

    uint64_t fat_length;
    uint64_t new_fat_length;

    uint64_t cluster_heap_offset;

    uint64_t bitmap_data_length;
    uint64_t bitmap_cluster_count;
    uint64_t upcase_cluster_count;

    uint64_t allocated_clusters;
    uint64_t root_first_cluster;
    uint64_t upcase_first_cluster;

    unsigned iteration;

    if (bytes_shift < 9u || bytes_shift > 12u) {
        return EXFAT_ERROR_GEOMETRY;
    }

    if (cluster_shift > (uint8_t)(25u - bytes_shift)) {
        return EXFAT_ERROR_GEOMETRY;
    }

    bytes_per_sector = 1ULL << bytes_shift;
    sectors_per_cluster = 1ULL << cluster_shift;
    bytes_per_cluster = bytes_per_sector * sectors_per_cluster;

    /*
     * Specification minimum volume size:
     *
     * 2^20 / bytes_per_sector sectors.
     */
    if (
        options->volume_length <
        ((1ULL << 20) / bytes_per_sector)
    ) {
        return EXFAT_ERROR_GEOMETRY;
    }

    if (options->volume_length <= EXFAT_MIN_FAT_OFFSET) {
        return EXFAT_ERROR_GEOMETRY;
    }

    /*
     * FatOffset is set to the minimum legal value: 24 sectors.
     *
     * FatLength and ClusterCount depend on each other.  Iterate until both
     * values become stable.
     */
    fat_length = 1u;
    cluster_count = 0u;

    for (iteration = 0; iteration < 64u; iteration++) {

        cluster_heap_offset =
            (uint64_t)EXFAT_MIN_FAT_OFFSET +
            fat_length;

        if (cluster_heap_offset >= options->volume_length) {
            return EXFAT_ERROR_GEOMETRY;
        }

        new_cluster_count =
            (options->volume_length - cluster_heap_offset) /
            sectors_per_cluster;

        if (
            new_cluster_count == 0u ||
            new_cluster_count > EXFAT_MAX_CLUSTER_COUNT
        ) {
            return EXFAT_ERROR_GEOMETRY;
        }

        /*
         * One 32-bit FAT entry for each cluster index:
         *
         * FatEntry[0]
         * FatEntry[1]
         * FatEntry[2] ... FatEntry[ClusterCount + 1]
         */
        new_fat_length =
            exfat_div_ceil_u64(
                (new_cluster_count + 2u) * 4u,
                bytes_per_sector
            );

        if (
            new_fat_length == fat_length &&
            new_cluster_count == cluster_count
        ) {
            break;
        }

        fat_length = new_fat_length;
        cluster_count = new_cluster_count;
    }

    if (iteration == 64u) {
        return EXFAT_ERROR_GEOMETRY;
    }

    cluster_heap_offset =
        (uint64_t)EXFAT_MIN_FAT_OFFSET +
        fat_length;

    cluster_count =
        (options->volume_length - cluster_heap_offset) /
        sectors_per_cluster;

    if (
        cluster_count == 0u ||
        cluster_count > EXFAT_MAX_CLUSTER_COUNT
    ) {
        return EXFAT_ERROR_GEOMETRY;
    }

    if (
        fat_length > UINT32_MAX ||
        cluster_heap_offset > UINT32_MAX ||
        cluster_count > UINT32_MAX
    ) {
        return EXFAT_ERROR_OVERFLOW;
    }

    /*
     * Allocation Bitmap:
     * one bit per Cluster Heap cluster.
     */
    bitmap_data_length =
        exfat_div_ceil_u64(
            cluster_count,
            8u
        );

    bitmap_cluster_count =
        exfat_div_ceil_u64(
            bitmap_data_length,
            bytes_per_cluster
        );

    /*
     * Full uncompressed UTF-16 Up-case Table:
     *
     * 65536 code units * 2 bytes = 131072 bytes.
     */
    upcase_cluster_count =
        exfat_div_ceil_u64(
            EXFAT_UPCASE_DATA_LENGTH,
            bytes_per_cluster
        );

    if (
        bitmap_cluster_count == 0u ||
        upcase_cluster_count == 0u
    ) {
        return EXFAT_ERROR_GEOMETRY;
    }

    /*
     * Metadata layout in the Cluster Heap:
     *
     * Cluster 2 ...
     *      Allocation Bitmap
     *
     * then
     *      Up-case Table
     *
     * then
     *      Root Directory (one cluster)
     *
     * All three allocations are contiguous internally.
     */
    allocated_clusters =
        bitmap_cluster_count +
        upcase_cluster_count +
        1u;

    if (allocated_clusters > cluster_count) {
        return EXFAT_ERROR_GEOMETRY;
    }

    upcase_first_cluster =
        2u + bitmap_cluster_count;

    root_first_cluster =
        upcase_first_cluster +
        upcase_cluster_count;

    if (
        root_first_cluster > UINT32_MAX ||
        bitmap_cluster_count > UINT32_MAX ||
        upcase_cluster_count > UINT32_MAX ||
        allocated_clusters > UINT32_MAX
    ) {
        return EXFAT_ERROR_OVERFLOW;
    }

    memset(geometry, 0, sizeof(*geometry));

    geometry->bytes_per_sector =
        (uint32_t)bytes_per_sector;

    geometry->sectors_per_cluster =
        (uint32_t)sectors_per_cluster;

    geometry->bytes_per_cluster =
        bytes_per_cluster;

    geometry->fat_offset =
        EXFAT_MIN_FAT_OFFSET;

    geometry->fat_length =
        (uint32_t)fat_length;

    geometry->cluster_heap_offset =
        (uint32_t)cluster_heap_offset;

    geometry->cluster_count =
        (uint32_t)cluster_count;

    geometry->bitmap_first_cluster =
        2u;

    geometry->bitmap_cluster_count =
        (uint32_t)bitmap_cluster_count;

    geometry->bitmap_data_length =
        bitmap_data_length;

    geometry->upcase_first_cluster =
        (uint32_t)upcase_first_cluster;

    geometry->upcase_cluster_count =
        (uint32_t)upcase_cluster_count;

    geometry->upcase_data_length =
        EXFAT_UPCASE_DATA_LENGTH;

    geometry->upcase_checksum =
        exfat_compute_generated_upcase_checksum();

    geometry->root_first_cluster =
        (uint32_t)root_first_cluster;

    geometry->root_cluster_count =
        1u;

    geometry->allocated_cluster_count =
        (uint32_t)allocated_clusters;

    /*
     * PercentInUse is rounded down to the nearest integer.
     */
    geometry->percent_in_use =
        (uint8_t)(
            ((uint64_t)geometry->allocated_cluster_count * 100u) /
            geometry->cluster_count
        );

    return EXFAT_OK;
}

/*
 * Calculate a legal formatter geometry.
 *
 * If sectors_per_cluster_shift == EXFAT_AUTO_CLUSTER_SHIFT, the formatter
 * begins with a 128-KiB policy and increases the cluster size until a valid
 * exFAT ClusterCount is obtained.
 */
int exfat_calculate_geometry(
    const exfat_format_options *options,
    exfat_geometry *geometry
)
{
    uint8_t bytes_shift;
    uint8_t cluster_shift;
    uint8_t maximum_cluster_shift;
    int result;

    if (options == NULL || geometry == NULL) {
        return EXFAT_ERROR_ARGUMENT;
    }

    bytes_shift =
        options->bytes_per_sector_shift == 0u
        ? EXFAT_DEFAULT_BYTES_SHIFT
        : options->bytes_per_sector_shift;

    if (bytes_shift < 9u || bytes_shift > 12u) {
        return EXFAT_ERROR_GEOMETRY;
    }

    maximum_cluster_shift =
        (uint8_t)(25u - bytes_shift);

    if (
        options->sectors_per_cluster_shift !=
        EXFAT_AUTO_CLUSTER_SHIFT
    ) {
        return exfat_calculate_for_cluster_shift(
            options,
            bytes_shift,
            options->sectors_per_cluster_shift,
            geometry
        );
    }

    cluster_shift =
        exfat_default_cluster_shift(bytes_shift);

    for (;;) {

        result =
            exfat_calculate_for_cluster_shift(
                options,
                bytes_shift,
                cluster_shift,
                geometry
            );

        if (result == EXFAT_OK) {
            return EXFAT_OK;
        }

        /*
         * Geometry failures caused by excessive ClusterCount may be resolved
         * by using a larger cluster.  Other invalid geometries will
         * eventually fail at the maximum allowed shift.
         */
        if (cluster_shift >= maximum_cluster_shift) {
            return result;
        }

        cluster_shift++;
    }
}

/* ------------------------------------------------------------------------- */
/* BOOT SECTOR / BOOT REGION                                                 */
/* ------------------------------------------------------------------------- */

static uint8_t exfat_log2_u32(uint32_t value)
{
    uint8_t shift = 0;

    while (value > 1u) {
        value >>= 1;
        shift++;
    }

    return shift;
}

static void exfat_init_boot_sector(
    exFAT_BootSector *vbr,
    const exfat_format_options *options,
    const exfat_geometry *geometry
)
{
    uint8_t bytes_shift;
    uint8_t cluster_shift;
    uint8_t drive_select;

    memset(vbr, 0, sizeof(*vbr));

    vbr->JumpBoot[0] = 0xEBu;
    vbr->JumpBoot[1] = 0x76u;
    vbr->JumpBoot[2] = 0x90u;

    memcpy(
        vbr->FileSystemName,
        "EXFAT   ",
        8u
    );

    /*
     * MustBeZero[] and Reserved[] remain zero after memset().
     */
    vbr->PartitionOffset =
        exfat_cpu_to_le64(
            options->partition_offset
        );

    vbr->VolumeLength =
        exfat_cpu_to_le64(
            options->volume_length
        );

    vbr->FatOffset =
        exfat_cpu_to_le32(
            geometry->fat_offset
        );

    vbr->FatLength =
        exfat_cpu_to_le32(
            geometry->fat_length
        );

    vbr->ClusterHeapOffset =
        exfat_cpu_to_le32(
            geometry->cluster_heap_offset
        );

    vbr->ClusterCount =
        exfat_cpu_to_le32(
            geometry->cluster_count
        );

    vbr->FirstClusterOfRootDirectory =
        exfat_cpu_to_le32(
            geometry->root_first_cluster
        );

    vbr->VolumeSerialNumber =
        exfat_cpu_to_le32(
            options->volume_serial
        );

    vbr->FileSystemRevision =
        exfat_cpu_to_le16(
            EXFAT_REVISION_1_00
        );

    /*
     * ActiveFat = 0
     * VolumeDirty = 0
     * MediaFailure = 0
     * ClearToZero = 0
     * Reserved = 0
     */
    vbr->VolumeFlags =
        exfat_cpu_to_le16(0u);

    bytes_shift =
        exfat_log2_u32(
            geometry->bytes_per_sector
        );

    cluster_shift =
        exfat_log2_u32(
            geometry->sectors_per_cluster
        );

    vbr->BytesPerSectorShift =
        bytes_shift;

    vbr->SectorsPerClusterShift =
        cluster_shift;

    /*
     * A single FAT is sufficient for a normal exFAT 1.00 volume.
     */
    vbr->NumberOfFats = 1u;

    drive_select =
        options->drive_select;

    vbr->DriveSelect =
        drive_select;

    vbr->PercentInUse =
        geometry->percent_in_use;

    /*
     * Formatter does not supply executable bootstrapping code.
     * F4h is the x86 HLT instruction and is explicitly permitted for
     * formatters which do not provide boot code.
     */
    memset(
        vbr->BootCode,
        0xF4,
        sizeof(vbr->BootCode)
    );

    vbr->BootSignature =
        exfat_cpu_to_le16(
            EXFAT_BOOT_SIGNATURE
        );
}

static int exfat_build_boot_region(
    const exfat_format_options *options,
    const exfat_geometry *geometry,
    uint8_t **out_region
)
{
    uint8_t *region;
    uint32_t bytes_per_sector;
    uint32_t sector;

    exFAT_BootSector boot_sector;
    uint32_t checksum;
    uint32_t checksum_le;

    size_t region_size;

    if (
        options == NULL ||
        geometry == NULL ||
        out_region == NULL
    ) {
        return EXFAT_ERROR_ARGUMENT;
    }

    bytes_per_sector =
        geometry->bytes_per_sector;

    region_size =
        (size_t)bytes_per_sector *
        EXFAT_BOOT_REGION_SECTORS;

    region =
        (uint8_t *)calloc(
            1u,
            region_size
        );

    if (region == NULL) {
        return EXFAT_ERROR_MEMORY;
    }

    exfat_init_boot_sector(
        &boot_sector,
        options,
        geometry
    );

    /*
     * Main / Backup Boot Sector occupies the first 512 bytes of its sector.
     * Excess bytes in sector sizes >512 remain zero/undefined.
     */
    memcpy(
        region,
        &boot_sector,
        sizeof(boot_sector)
    );

    /*
     * Extended Boot Sectors 1..8.
     *
     * ExtendedBootCode is zero.
     * ExtendedBootSignature occupies the final four bytes.
     */
    for (sector = 1u; sector <= 8u; sector++) {

        uint32_t signature_le =
            exfat_cpu_to_le32(
                EXFAT_EXTENDED_BOOT_SIGNATURE
            );

        memcpy(
            region +
            ((size_t)sector * bytes_per_sector) +
            bytes_per_sector -
            sizeof(signature_le),
            &signature_le,
            sizeof(signature_le)
        );
    }

    /*
     * Sector 9: OEM Parameters
     *
     * All ten parameter slots are Null Parameters.  Because a Null
     * Parameters structure is a zero GUID followed by zero reserved bytes,
     * the calloc() initialization already produces the correct contents.
     *
     * Sector 10: Reserved
     *
     * Must be zero, also already satisfied.
     */

    checksum =
        BootChecksum(
            region,
            (USHORT)bytes_per_sector
        );

    checksum_le =
        exfat_cpu_to_le32(checksum);

    /*
     * Sector 11: Boot Checksum.
     *
     * Fill the entire sector with a repeating four-byte checksum pattern.
     */
    for (
        sector = 0u;
        sector < bytes_per_sector;
        sector += 4u
    ) {
        memcpy(
            region +
            ((size_t)11u * bytes_per_sector) +
            sector,
            &checksum_le,
            sizeof(checksum_le)
        );
    }

    *out_region = region;
    return EXFAT_OK;
}

/* ------------------------------------------------------------------------- */
/* I/O HELPERS                                                               */
/* ------------------------------------------------------------------------- */

static int exfat_write_volume_sectors(
    const exfat_io *io,
    const exfat_format_options *options,
    uint64_t volume_relative_sector,
    const void *data,
    uint32_t sector_count
)
{
    uint64_t media_lba;
    uint64_t end_sector;

    if (
        io == NULL ||
        io->write_sectors == NULL ||
        options == NULL ||
        data == NULL ||
        sector_count == 0u
    ) {
        return EXFAT_ERROR_ARGUMENT;
    }

    if (
        exfat_add_u64_overflow(
            volume_relative_sector,
            sector_count,
            &end_sector
        )
    ) {
        return EXFAT_ERROR_OVERFLOW;
    }

    if (end_sector > options->volume_length) {
        return EXFAT_ERROR_GEOMETRY;
    }

    if (
        exfat_add_u64_overflow(
            options->partition_offset,
            volume_relative_sector,
            &media_lba
        )
    ) {
        return EXFAT_ERROR_OVERFLOW;
    }

    if (
        io->write_sectors(
            io->context,
            media_lba,
            data,
            sector_count
        ) != 0
    ) {
        return EXFAT_ERROR_IO;
    }

    return EXFAT_OK;
}

/* ------------------------------------------------------------------------- */
/* FAT                                                                       */
/* ------------------------------------------------------------------------- */

static int exfat_cluster_is_in_chain(
    uint32_t cluster,
    uint32_t first_cluster,
    uint32_t cluster_count
)
{
    uint64_t end =
        (uint64_t)first_cluster +
        cluster_count;

    return
        cluster >= first_cluster &&
        (uint64_t)cluster < end;
}

static uint32_t exfat_fat_value_for_index(
    uint32_t index,
    const exfat_geometry *geometry
)
{
    uint32_t relative;

    if (index == 0u) {
        return EXFAT_FAT_MEDIA_ENTRY;
    }

    if (index == 1u) {
        return EXFAT_FAT_EOF;
    }

    /*
     * Allocation Bitmap chain.
     */
    if (
        exfat_cluster_is_in_chain(
            index,
            geometry->bitmap_first_cluster,
            geometry->bitmap_cluster_count
        )
    ) {
        relative =
            index -
            geometry->bitmap_first_cluster;

        if (
            relative + 1u <
            geometry->bitmap_cluster_count
        ) {
            return index + 1u;
        }

        return EXFAT_FAT_EOF;
    }

    /*
     * Up-case Table chain.
     */
    if (
        exfat_cluster_is_in_chain(
            index,
            geometry->upcase_first_cluster,
            geometry->upcase_cluster_count
        )
    ) {
        relative =
            index -
            geometry->upcase_first_cluster;

        if (
            relative + 1u <
            geometry->upcase_cluster_count
        ) {
            return index + 1u;
        }

        return EXFAT_FAT_EOF;
    }

    /*
     * Root Directory chain.
     */
    if (
        exfat_cluster_is_in_chain(
            index,
            geometry->root_first_cluster,
            geometry->root_cluster_count
        )
    ) {
        relative =
            index -
            geometry->root_first_cluster;

        if (
            relative + 1u <
            geometry->root_cluster_count
        ) {
            return index + 1u;
        }

        return EXFAT_FAT_EOF;
    }

    /*
     * All remaining clusters are free.
     */
    return 0u;
}

static int exfat_write_fat(
    const exfat_io *io,
    const exfat_format_options *options,
    const exfat_geometry *geometry
)
{
    uint8_t *sector_buffer;
    uint32_t bytes_per_sector;
    uint32_t entries_per_sector;

    uint32_t fat_sector;

    int result;

    bytes_per_sector =
        geometry->bytes_per_sector;

    entries_per_sector =
        bytes_per_sector / 4u;

    sector_buffer =
        (uint8_t *)calloc(
            1u,
            bytes_per_sector
        );

    if (sector_buffer == NULL) {
        return EXFAT_ERROR_MEMORY;
    }

    for (
        fat_sector = 0u;
        fat_sector < geometry->fat_length;
        fat_sector++
    ) {
        uint32_t slot;

        memset(
            sector_buffer,
            0,
            bytes_per_sector
        );

        for (
            slot = 0u;
            slot < entries_per_sector;
            slot++
        ) {
            uint64_t index64 =
                (uint64_t)fat_sector *
                entries_per_sector +
                slot;

            uint32_t value;
            uint32_t value_le;

            /*
             * Entries after ClusterCount+1 are ExcessSpace.
             * Zero is a harmless formatter value for that undefined area.
             */
            if (
                index64 >
                (uint64_t)geometry->cluster_count + 1u
            ) {
                break;
            }

            value =
                exfat_fat_value_for_index(
                    (uint32_t)index64,
                    geometry
                );

            value_le =
                exfat_cpu_to_le32(value);

            memcpy(
                sector_buffer +
                ((size_t)slot * 4u),
                &value_le,
                4u
            );
        }

        result =
            exfat_write_volume_sectors(
                io,
                options,
                (uint64_t)geometry->fat_offset +
                    fat_sector,
                sector_buffer,
                1u
            );

        if (result != EXFAT_OK) {
            free(sector_buffer);
            return result;
        }
    }

    free(sector_buffer);
    return EXFAT_OK;
}

/* ------------------------------------------------------------------------- */
/* CLUSTER ADDRESSING                                                        */
/* ------------------------------------------------------------------------- */

static uint64_t exfat_cluster_to_volume_sector(
    const exfat_geometry *geometry,
    uint32_t cluster
)
{
    return
        (uint64_t)geometry->cluster_heap_offset +
        ((uint64_t)cluster - 2u) *
        geometry->sectors_per_cluster;
}

/* ------------------------------------------------------------------------- */
/* ALLOCATION BITMAP                                                         */
/* ------------------------------------------------------------------------- */

static uint8_t exfat_bitmap_byte(
    uint64_t byte_index,
    uint32_t allocated_cluster_count,
    uint64_t bitmap_data_length
)
{
    uint64_t first_bit;
    uint64_t used_bits;

    if (byte_index >= bitmap_data_length) {
        return 0u;
    }

    first_bit =
        byte_index * 8u;

    used_bits =
        allocated_cluster_count;

    if (first_bit + 8u <= used_bits) {
        return 0xFFu;
    }

    if (first_bit >= used_bits) {
        return 0u;
    }

    /*
     * First bitmap bit is the least-order bit of the first byte.
     */
    {
        uint32_t bits =
            (uint32_t)(used_bits - first_bit);

        return
            (uint8_t)((1u << bits) - 1u);
    }
}

static int exfat_write_allocation_bitmap(
    const exfat_io *io,
    const exfat_format_options *options,
    const exfat_geometry *geometry
)
{
    uint8_t *sector_buffer;
    uint64_t allocation_sectors;
    uint64_t sector_index;
    uint64_t start_sector;

    uint32_t bytes_per_sector;
    int result;

    bytes_per_sector =
        geometry->bytes_per_sector;

    allocation_sectors =
        (uint64_t)geometry->bitmap_cluster_count *
        geometry->sectors_per_cluster;

    start_sector =
        exfat_cluster_to_volume_sector(
            geometry,
            geometry->bitmap_first_cluster
        );

    sector_buffer =
        (uint8_t *)malloc(
            bytes_per_sector
        );

    if (sector_buffer == NULL) {
        return EXFAT_ERROR_MEMORY;
    }

    for (
        sector_index = 0u;
        sector_index < allocation_sectors;
        sector_index++
    ) {
        uint32_t byte_offset;

        for (
            byte_offset = 0u;
            byte_offset < bytes_per_sector;
            byte_offset++
        ) {
            uint64_t bitmap_byte_index =
                sector_index *
                bytes_per_sector +
                byte_offset;

            sector_buffer[byte_offset] =
                exfat_bitmap_byte(
                    bitmap_byte_index,
                    geometry->allocated_cluster_count,
                    geometry->bitmap_data_length
                );
        }

        result =
            exfat_write_volume_sectors(
                io,
                options,
                start_sector + sector_index,
                sector_buffer,
                1u
            );

        if (result != EXFAT_OK) {
            free(sector_buffer);
            return result;
        }
    }

    free(sector_buffer);
    return EXFAT_OK;
}

/* ------------------------------------------------------------------------- */
/* UP-CASE TABLE                                                             */
/* ------------------------------------------------------------------------- */

static int exfat_write_upcase_table(
    const exfat_io *io,
    const exfat_format_options *options,
    const exfat_geometry *geometry
)
{
    uint8_t *sector_buffer;

    uint64_t allocated_sectors;
    uint64_t data_sectors;
    uint64_t sector_index;
    uint64_t start_sector;

    uint32_t bytes_per_sector;
    int result;

    bytes_per_sector =
        geometry->bytes_per_sector;

    allocated_sectors =
        (uint64_t)geometry->upcase_cluster_count *
        geometry->sectors_per_cluster;

    data_sectors =
        EXFAT_UPCASE_DATA_LENGTH /
        bytes_per_sector;

    start_sector =
        exfat_cluster_to_volume_sector(
            geometry,
            geometry->upcase_first_cluster
        );

    sector_buffer =
        (uint8_t *)calloc(
            1u,
            bytes_per_sector
        );

    if (sector_buffer == NULL) {
        return EXFAT_ERROR_MEMORY;
    }

    for (
        sector_index = 0u;
        sector_index < allocated_sectors;
        sector_index++
    ) {
        memset(
            sector_buffer,
            0,
            bytes_per_sector
        );

        if (sector_index < data_sectors) {

            uint32_t byte_offset;

            for (
                byte_offset = 0u;
                byte_offset < bytes_per_sector;
                byte_offset += 2u
            ) {
                uint64_t data_byte_offset =
                    sector_index *
                    bytes_per_sector +
                    byte_offset;

                uint32_t code_unit_index =
                    (uint32_t)(
                        data_byte_offset / 2u
                    );

                uint16_t mapped =
                    exfat_upcase_mapping(
                        (uint16_t)code_unit_index
                    );

                sector_buffer[byte_offset] =
                    (uint8_t)(
                        mapped & 0xFFu
                    );

                sector_buffer[byte_offset + 1u] =
                    (uint8_t)(
                        (mapped >> 8) & 0xFFu
                    );
            }
        }

        result =
            exfat_write_volume_sectors(
                io,
                options,
                start_sector + sector_index,
                sector_buffer,
                1u
            );

        if (result != EXFAT_OK) {
            free(sector_buffer);
            return result;
        }
    }

    free(sector_buffer);
    return EXFAT_OK;
}

/* ------------------------------------------------------------------------- */
/* ROOT DIRECTORY                                                            */
/* ------------------------------------------------------------------------- */

static void exfat_init_allocation_bitmap_entry(
    exFAT_AllocationBitmapEntry *entry,
    const exfat_geometry *geometry
)
{
    memset(
        entry,
        0,
        sizeof(*entry)
    );

    entry->EntryType =
        EXFAT_ENTRY_ALLOCATION_BITMAP;

    /*
     * BitmapIdentifier = 0 -> First Allocation Bitmap.
     */
    entry->BitmapFlags = 0u;

    entry->FirstCluster =
        exfat_cpu_to_le32(
            geometry->bitmap_first_cluster
        );

    entry->DataLength =
        exfat_cpu_to_le64(
            geometry->bitmap_data_length
        );
}

static void exfat_init_upcase_entry(
    exFAT_UpcaseTableEntry *entry,
    const exfat_geometry *geometry
)
{
    memset(
        entry,
        0,
        sizeof(*entry)
    );

    entry->EntryType =
        EXFAT_ENTRY_UPCASE_TABLE;

    entry->TableChecksum =
        exfat_cpu_to_le32(
            geometry->upcase_checksum
        );

    entry->FirstCluster =
        exfat_cpu_to_le32(
            geometry->upcase_first_cluster
        );

    entry->DataLength =
        exfat_cpu_to_le64(
            geometry->upcase_data_length
        );
}

static int exfat_write_root_directory(
    const exfat_io *io,
    const exfat_format_options *options,
    const exfat_geometry *geometry
)
{
    uint8_t *sector_buffer;

    exFAT_AllocationBitmapEntry bitmap_entry;
    exFAT_UpcaseTableEntry upcase_entry;

    uint64_t start_sector;
    uint32_t sector_index;

    int result;

    sector_buffer =
        (uint8_t *)calloc(
            1u,
            geometry->bytes_per_sector
        );

    if (sector_buffer == NULL) {
        return EXFAT_ERROR_MEMORY;
    }

    exfat_init_allocation_bitmap_entry(
        &bitmap_entry,
        geometry
    );

    exfat_init_upcase_entry(
        &upcase_entry,
        geometry
    );

    /*
     * Root directory starts with:
     *
     *   DirectoryEntry[0] = Allocation Bitmap
     *   DirectoryEntry[1] = Up-case Table
     *   DirectoryEntry[2] = 00h end-of-directory marker
     *
     * calloc() leaves the third and all subsequent entries zero.
     */
    memcpy(
        sector_buffer,
        &bitmap_entry,
        sizeof(bitmap_entry)
    );

    memcpy(
        sector_buffer + 32u,
        &upcase_entry,
        sizeof(upcase_entry)
    );

    start_sector =
        exfat_cluster_to_volume_sector(
            geometry,
            geometry->root_first_cluster
        );

    for (
        sector_index = 0u;
        sector_index <
            geometry->sectors_per_cluster;
        sector_index++
    ) {
        if (sector_index != 0u) {

            memset(
                sector_buffer,
                0,
                geometry->bytes_per_sector
            );
        }

        result =
            exfat_write_volume_sectors(
                io,
                options,
                start_sector + sector_index,
                sector_buffer,
                1u
            );

        if (result != EXFAT_OK) {
            free(sector_buffer);
            return result;
        }
    }

    free(sector_buffer);
    return EXFAT_OK;
}

/* ------------------------------------------------------------------------- */
/* BOOT REGION WRITE                                                        */
/* ------------------------------------------------------------------------- */

static int exfat_write_boot_regions(
    const exfat_io *io,
    const exfat_format_options *options,
    const exfat_geometry *geometry
)
{
    uint8_t *region = NULL;
    int result;

    result =
        exfat_build_boot_region(
            options,
            geometry,
            &region
        );

    if (result != EXFAT_OK) {
        return result;
    }

    /*
     * Main Boot Region: sectors 0..11.
     */
    result =
        exfat_write_volume_sectors(
            io,
            options,
            EXFAT_MAIN_BOOT_START,
            region,
            EXFAT_BOOT_REGION_SECTORS
        );

    if (result != EXFAT_OK) {
        free(region);
        return result;
    }

    /*
     * Backup Boot Region: sectors 12..23.
     *
     * Initial format populates it as a copy of the Main Boot Region.
     */
    result =
        exfat_write_volume_sectors(
            io,
            options,
            EXFAT_BACKUP_BOOT_START,
            region,
            EXFAT_BOOT_REGION_SECTORS
        );

    free(region);
    return result;
}

/* ------------------------------------------------------------------------- */
/* BASIC POST-BUILD VALIDATION                                               */
/* ------------------------------------------------------------------------- */

int exfat_validate_geometry(
    const exfat_format_options *options,
    const exfat_geometry *geometry
)
{
    uint64_t cluster_heap_end;
    uint64_t required_fat_bytes;
    uint64_t actual_fat_bytes;
    uint64_t metadata_last_cluster;

    if (
        options == NULL ||
        geometry == NULL
    ) {
        return EXFAT_ERROR_ARGUMENT;
    }

    if (
        geometry->bytes_per_sector < 512u ||
        geometry->bytes_per_sector > 4096u
    ) {
        return EXFAT_ERROR_GEOMETRY;
    }

    if (
        geometry->bytes_per_cluster >
        (32ULL * 1024u * 1024u)
    ) {
        return EXFAT_ERROR_GEOMETRY;
    }

    if (
        geometry->fat_offset < 24u ||
        geometry->fat_length == 0u
    ) {
        return EXFAT_ERROR_GEOMETRY;
    }

    if (
        geometry->cluster_heap_offset <
        geometry->fat_offset +
        geometry->fat_length
    ) {
        return EXFAT_ERROR_GEOMETRY;
    }

    required_fat_bytes =
        ((uint64_t)geometry->cluster_count + 2u) *
        4u;

    actual_fat_bytes =
        (uint64_t)geometry->fat_length *
        geometry->bytes_per_sector;

    if (actual_fat_bytes < required_fat_bytes) {
        return EXFAT_ERROR_GEOMETRY;
    }

    cluster_heap_end =
        (uint64_t)geometry->cluster_heap_offset +
        (uint64_t)geometry->cluster_count *
        geometry->sectors_per_cluster;

    if (cluster_heap_end > options->volume_length) {
        return EXFAT_ERROR_GEOMETRY;
    }

    if (
        geometry->cluster_count == 0u ||
        geometry->cluster_count >
        EXFAT_MAX_CLUSTER_COUNT
    ) {
        return EXFAT_ERROR_GEOMETRY;
    }

    metadata_last_cluster =
        (uint64_t)geometry->root_first_cluster +
        geometry->root_cluster_count -
        1u;

    if (
        metadata_last_cluster >
        (uint64_t)geometry->cluster_count + 1u
    ) {
        return EXFAT_ERROR_GEOMETRY;
    }

    if (
        geometry->bitmap_data_length !=
        exfat_div_ceil_u64(
            geometry->cluster_count,
            8u
        )
    ) {
        return EXFAT_ERROR_GEOMETRY;
    }

    if (
        geometry->upcase_data_length !=
        EXFAT_UPCASE_DATA_LENGTH
    ) {
        return EXFAT_ERROR_GEOMETRY;
    }

    return EXFAT_OK;
}

/* ------------------------------------------------------------------------- */
/* COMPLETE FORMAT OPERATION                                                 */
/* ------------------------------------------------------------------------- */

/*
 * exfat_format()
 *
 * Formats an EMPTY exFAT volume using the supplied sector writer.
 *
 * On success:
 *
 *   - Main Boot Region exists
 *   - Backup Boot Region exists
 *   - First FAT exists
 *   - Allocation Bitmap exists
 *   - Up-case Table exists
 *   - Root Directory contains the required metadata entries
 *   - all non-metadata clusters are marked free
 *
 * No user files are created.
 */
int exfat_format(
    const exfat_io *io,
    const exfat_format_options *options,
    exfat_geometry *out_geometry
)
{
    exfat_geometry geometry;
    uint64_t partition_end;

    int result;

    if (
        io == NULL ||
        io->write_sectors == NULL ||
        options == NULL
    ) {
        return EXFAT_ERROR_ARGUMENT;
    }

    if (
        exfat_add_u64_overflow(
            options->partition_offset,
            options->volume_length,
            &partition_end
        )
    ) {
        return EXFAT_ERROR_OVERFLOW;
    }

    (void)partition_end;

    result =
        exfat_calculate_geometry(
            options,
            &geometry
        );

    if (result != EXFAT_OK) {
        return result;
    }

    result =
        exfat_validate_geometry(
            options,
            &geometry
        );

    if (result != EXFAT_OK) {
        return result;
    }

    /*
     * Initial-format write order.
     *
     * 1. Boot Regions
     * 2. FAT
     * 3. Allocation Bitmap
     * 4. Up-case Table
     * 5. Root Directory
     *
     * The volume is not exposed as a valid mounted filesystem until the
     * caller considers the operation complete.
     */
    result =
        exfat_write_boot_regions(
            io,
            options,
            &geometry
        );

    if (result != EXFAT_OK) {
        return result;
    }

    result =
        exfat_write_fat(
            io,
            options,
            &geometry
        );

    if (result != EXFAT_OK) {
        return result;
    }

    result =
        exfat_write_allocation_bitmap(
            io,
            options,
            &geometry
        );

    if (result != EXFAT_OK) {
        return result;
    }

    result =
        exfat_write_upcase_table(
            io,
            options,
            &geometry
        );

    if (result != EXFAT_OK) {
        return result;
    }

    result =
        exfat_write_root_directory(
            io,
            options,
            &geometry
        );

    if (result != EXFAT_OK) {
        return result;
    }

    if (io->flush != NULL) {

        if (
            io->flush(
                io->context
            ) != 0
        ) {
            return EXFAT_ERROR_IO;
        }
    }

    if (out_geometry != NULL) {
        *out_geometry = geometry;
    }

    return EXFAT_OK;
}

/* ------------------------------------------------------------------------- */
/* OPTIONAL CONVENIENCE INITIALIZER                                          */
/* ------------------------------------------------------------------------- */

/*
 * Convenience defaults for callers.
 *
 * The caller still has to fill:
 *
 *   partition_offset
 *   volume_length
 *   volume_serial
 *
 * The defaults are:
 *
 *   512-byte sectors
 *   automatic cluster sizing (starts at ~128 KiB)
 *   DriveSelect 80h
 */
void exfat_format_options_init(
    exfat_format_options *options
)
{
    if (options == NULL) {
        return;
    }

    memset(
        options,
        0,
        sizeof(*options)
    );

    options->bytes_per_sector_shift =
        EXFAT_DEFAULT_BYTES_SHIFT;

    options->sectors_per_cluster_shift =
        EXFAT_AUTO_CLUSTER_SHIFT;

    options->drive_select =
        EXFAT_DEFAULT_DRIVE_SELECT;
}

/* ------------------------------------------------------------------------- */
/* HUMAN-READABLE ERROR STRING                                               */
/* ------------------------------------------------------------------------- */

const char *exfat_error_string(int error)
{
    switch (error) {

        case EXFAT_OK:
            return "success";

        case EXFAT_ERROR_ARGUMENT:
            return "invalid argument";

        case EXFAT_ERROR_GEOMETRY:
            return "invalid or unsupported exFAT geometry";

        case EXFAT_ERROR_MEMORY:
            return "memory allocation failed";

        case EXFAT_ERROR_IO:
            return "sector I/O failed";

        case EXFAT_ERROR_OVERFLOW:
            return "integer or address overflow";

        default:
            return "unknown exFAT error";
    }
}
