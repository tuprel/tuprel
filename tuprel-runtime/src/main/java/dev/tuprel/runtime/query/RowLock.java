package dev.tuprel.runtime.query;

import dev.tuprel.sql.SqlQuery;

/**
 * Row lock on the rows a query returns. Locks require an explicit transaction and are held
 * until it ends; relations loaded with {@code include} are not locked.
 */
public enum RowLock {
    /** Exclusive lock; waits for concurrent lock holders. */
    FOR_UPDATE(SqlQuery.Lock.Strength.UPDATE, SqlQuery.Lock.Wait.WAIT),
    /** Exclusive lock; fails immediately if a row is locked. */
    FOR_UPDATE_NOWAIT(SqlQuery.Lock.Strength.UPDATE, SqlQuery.Lock.Wait.NOWAIT),
    /** Exclusive lock; omits rows locked by other transactions. */
    FOR_UPDATE_SKIP_LOCKED(SqlQuery.Lock.Strength.UPDATE, SqlQuery.Lock.Wait.SKIP_LOCKED),
    /** Shared lock; waits for concurrent exclusive lock holders. */
    FOR_SHARE(SqlQuery.Lock.Strength.SHARE, SqlQuery.Lock.Wait.WAIT),
    /** Shared lock; fails immediately if a row is exclusively locked. */
    FOR_SHARE_NOWAIT(SqlQuery.Lock.Strength.SHARE, SqlQuery.Lock.Wait.NOWAIT),
    /** Shared lock; omits rows exclusively locked by other transactions. */
    FOR_SHARE_SKIP_LOCKED(SqlQuery.Lock.Strength.SHARE, SqlQuery.Lock.Wait.SKIP_LOCKED);

    private final SqlQuery.Lock.Strength strength;
    private final SqlQuery.Lock.Wait waitPolicy;

    RowLock(SqlQuery.Lock.Strength strength, SqlQuery.Lock.Wait waitPolicy) {
        this.strength = strength;
        this.waitPolicy = waitPolicy;
    }

    SqlQuery.Lock toSql() {
        return new SqlQuery.Lock(strength, waitPolicy);
    }
}
