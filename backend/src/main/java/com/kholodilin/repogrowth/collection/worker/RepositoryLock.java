package com.kholodilin.repogrowth.collection.worker;

import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

@Component
public class RepositoryLock {

    private static final int LOCK_NAMESPACE = 17;

    private final DataSource dataSource;

    public RepositoryLock(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public Connection openConnection() throws SQLException {
        Connection connection = dataSource.getConnection();
        connection.setAutoCommit(true);
        return connection;
    }

    public boolean tryLock(Connection connection, long repositoryId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT pg_try_advisory_lock(?, ?)")) {
            statement.setInt(1, LOCK_NAMESPACE);
            statement.setInt(2, lockKey(repositoryId));
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                return rs.getBoolean(1);
            }
        }
    }

    public void unlock(Connection connection, long repositoryId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT pg_advisory_unlock(?, ?)")) {
            statement.setInt(1, LOCK_NAMESPACE);
            statement.setInt(2, lockKey(repositoryId));
            statement.executeQuery().close();
        }
    }

    private int lockKey(long repositoryId) {
        return Long.hashCode(repositoryId) & Integer.MAX_VALUE;
    }
}
