package dev.tuprel.migrate.postgresql;

/** Fixed PostgreSQL 17 metadata queries. Never deparses expressions or opens a user relation. */
final class CatalogQueries {
    private static final String SCOPE = """
            WITH scope AS (
                SELECT * FROM pg_catalog.pg_class WHERE relnamespace = ?::pg_catalog.oid
            )
            """;

    static final String RELATIONS = SCOPE + """
            SELECT c.oid, c.relname, c.relkind, c.relnatts,
                   coalesce(c.relpersistence = 'p' AND NOT c.relispartition
                   AND c.reloftype = 0 AND c.reltablespace = 0 AND c.reloptions IS NULL
                   AND NOT c.relrowsecurity AND NOT c.relforcerowsecurity
                   AND ((c.relkind = 'r' AND c.relreplident = 'd' AND am.amname = 'heap' AND am.amtype = 't')
                        OR (c.relkind = 'i' AND am.amname = 'btree' AND am.amtype = 'i'))
                   AND NOT EXISTS (SELECT 1 FROM pg_catalog.pg_inherits h WHERE h.inhrelid = c.oid OR h.inhparent = c.oid)
                   AND NOT EXISTS (SELECT 1 FROM pg_catalog.pg_trigger t WHERE t.tgrelid = c.oid AND NOT t.tgisinternal)
                   AND NOT EXISTS (SELECT 1 FROM pg_catalog.pg_rewrite r WHERE r.ev_class = c.oid)
                   AND NOT EXISTS (SELECT 1 FROM pg_catalog.pg_policy p WHERE p.polrelid = c.oid)
                   AND NOT EXISTS (SELECT 1 FROM pg_catalog.pg_depend d
                                   WHERE d.classid = 'pg_catalog.pg_class'::pg_catalog.regclass
                                     AND d.objid = c.oid AND d.deptype IN ('e', 'x')), false) AS supported,
                   CASE WHEN c.relkind = 'r' THEN
                       EXISTS (SELECT 1 FROM pg_catalog.pg_type t JOIN pg_catalog.pg_type a ON a.oid = t.typarray
                               WHERE t.oid = c.reltype AND t.typrelid = c.oid AND t.typtype = 'c'
                                 AND t.typnamespace = c.relnamespace AND a.typnamespace = c.relnamespace
                                 AND a.typelem = t.oid AND a.typcategory = 'A')
                       AND (c.reltoastrelid = 0 OR EXISTS (
                           SELECT 1 FROM pg_catalog.pg_class t JOIN pg_catalog.pg_namespace n ON n.oid = t.relnamespace
                           JOIN pg_catalog.pg_depend d ON d.objid = t.oid AND d.refobjid = c.oid
                           WHERE t.oid = c.reltoastrelid AND t.relkind = 't' AND n.nspname = 'pg_toast'
                             AND d.classid = 'pg_catalog.pg_class'::pg_catalog.regclass
                             AND d.refclassid = 'pg_catalog.pg_class'::pg_catalog.regclass
                             AND d.objsubid = 0 AND d.refobjsubid = 0 AND d.deptype = 'i'))
                   WHEN c.relkind = 'i' THEN c.reltype = 0 AND c.reltoastrelid = 0 ELSE true END AS linked
            FROM scope c LEFT JOIN pg_catalog.pg_am am ON am.oid = c.relam
            """;

    static final String TYPES = SCOPE + """
            SELECT t.oid,
                   EXISTS (SELECT 1 FROM scope c
                           JOIN pg_catalog.pg_type r ON r.oid = c.reltype AND r.typrelid = c.oid
                           WHERE c.relkind = 'r' AND (r.oid = t.oid OR (r.typarray = t.oid AND t.typelem = r.oid)))
                   AND NOT EXISTS (SELECT 1 FROM pg_catalog.pg_depend d
                                   WHERE d.classid = 'pg_catalog.pg_type'::pg_catalog.regclass
                                     AND d.objid = t.oid AND d.deptype IN ('e', 'x'))
            FROM pg_catalog.pg_type t WHERE t.typnamespace = ?::pg_catalog.oid
            """;

    static final String COLUMNS = SCOPE + """
            SELECT c.oid, a.attnum, a.attname, a.atttypid, a.atttypmod, a.attnotnull, a.attisdropped,
                   CASE WHEN a.attisdropped THEN true ELSE t.oid IS NOT NULL END AS linked,
                   coalesce(t.typnamespace = 'pg_catalog'::pg_catalog.regnamespace AND t.typtype = 'b'
                   AND a.attcollation = t.typcollation AND a.attstorage = t.typstorage AND a.attcompression = ''
                   AND a.attndims = 0 AND a.attislocal AND a.attinhcount = 0 AND NOT a.atthasdef AND NOT a.atthasmissing
                   AND a.attidentity = '' AND a.attgenerated = '' AND a.attoptions IS NULL AND a.attfdwoptions IS NULL
                   AND NOT EXISTS (SELECT 1 FROM pg_catalog.pg_attrdef d
                                   WHERE d.adrelid = c.oid AND d.adnum = a.attnum), false) AS supported
            FROM scope c JOIN pg_catalog.pg_attribute a ON a.attrelid = c.oid AND a.attnum > 0
            LEFT JOIN pg_catalog.pg_type t ON t.oid = a.atttypid
            WHERE c.relkind = 'r'
            """;

    static final String CONSTRAINTS = SCOPE + """
            SELECT x.oid, x.conrelid, x.confrelid, x.contype, x.conindid, x.conkey[1],
                   x.convalidated AND NOT x.condeferrable AND NOT x.condeferred AND x.conparentid = 0
                   AND x.conislocal AND x.coninhcount = 0 AND x.connamespace = r.relnamespace
                   AND NOT EXISTS (SELECT 1 FROM pg_catalog.pg_depend d
                                   WHERE d.classid = 'pg_catalog.pg_constraint'::pg_catalog.regclass
                                     AND d.objid = x.oid AND d.deptype IN ('e', 'x')) AS supported,
                   pg_catalog.cardinality(x.conkey)
            FROM pg_catalog.pg_constraint x
            LEFT JOIN pg_catalog.pg_class r ON r.oid = x.conrelid
            WHERE x.conrelid IN (SELECT oid FROM scope) OR x.confrelid IN (SELECT oid FROM scope)
            """;

    static final String INDEXES = SCOPE + """
            SELECT i.indexrelid, i.indrelid, i.indnatts, i.indnkeyatts, i.indkey::pg_catalog.text, i.indisprimary,
                   idx.oid IS NOT NULL AND c.oid IS NOT NULL AND idx.relnatts = i.indnatts
                   AND NOT EXISTS (
                       SELECT 1 FROM pg_catalog.generate_subscripts(i.indkey, 1) AS pos(n)
                       LEFT JOIN pg_catalog.pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = i.indkey[pos.n]
                       LEFT JOIN pg_catalog.pg_opclass op ON op.oid = i.indclass[pos.n]
                       LEFT JOIN pg_catalog.pg_opfamily f ON f.oid = op.opcfamily
                       WHERE (i.indkey[pos.n] > 0 AND (a.attnum IS NULL OR a.attisdropped))
                          OR (pos.n < i.indnkeyatts AND (op.oid IS NULL OR f.oid IS NULL))
                   ) AS linked,
                   coalesce(idx.relnamespace = c.relnamespace AND idx.relkind = 'i'
                   AND i.indisunique = i.indisprimary AND NOT i.indnullsnotdistinct AND NOT i.indisexclusion
                   AND (NOT i.indisprimary OR i.indimmediate)
                   AND i.indisvalid AND i.indisready AND i.indislive AND NOT i.indisreplident
                   AND i.indexprs IS NULL AND i.indpred IS NULL AND i.indnatts = i.indnkeyatts
                   AND NOT EXISTS (
                       SELECT 1
                       FROM pg_catalog.generate_subscripts(i.indkey, 1) AS pos(n)
                       LEFT JOIN pg_catalog.pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = i.indkey[pos.n]
                       LEFT JOIN pg_catalog.pg_opclass op ON op.oid = i.indclass[pos.n]
                       LEFT JOIN pg_catalog.pg_opfamily f ON f.oid = op.opcfamily
                       WHERE (i.indkey[pos.n] > 0 AND NOT a.attisdropped
                              AND i.indoption[pos.n] = 0 AND i.indcollation[pos.n] = a.attcollation
                              AND op.opcdefault AND op.opcmethod = idx.relam AND f.opfmethod = idx.relam
                              AND op.opcnamespace = 'pg_catalog'::pg_catalog.regnamespace
                              AND f.opfnamespace = 'pg_catalog'::pg_catalog.regnamespace
                              AND op.opcintype = CASE WHEN a.atttypid = 1043 THEN 25::pg_catalog.oid ELSE a.atttypid END
                              AND NOT EXISTS (SELECT 1 FROM pg_catalog.pg_depend d WHERE d.deptype IN ('e', 'x')
                                  AND ((d.classid = 'pg_catalog.pg_opclass'::pg_catalog.regclass AND d.objid = op.oid)
                                    OR (d.classid = 'pg_catalog.pg_opfamily'::pg_catalog.regclass AND d.objid = f.oid))))
                              IS DISTINCT FROM true
                   ), false) AS supported
            FROM pg_catalog.pg_index i
            LEFT JOIN pg_catalog.pg_class idx ON idx.oid = i.indexrelid
            LEFT JOIN pg_catalog.pg_class c ON c.oid = i.indrelid
            WHERE i.indexrelid IN (SELECT oid FROM scope) OR i.indrelid IN (SELECT oid FROM scope)
            """;

    private CatalogQueries() { }
}
