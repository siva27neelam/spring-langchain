package com.loanprocessing.support;

import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.datasource.DelegatingDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

public class FaultDataSource extends DelegatingDataSource {
    private final FaultSwitch faults;

    public FaultDataSource(DataSource target, FaultSwitch faults) {
        super(target);
        this.faults = faults;
    }

    @Override
    public Connection getConnection() throws SQLException {
        refuse();
        return super.getConnection();
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        refuse();
        return super.getConnection(username, password);
    }

    private void refuse() {
        if (faults.databaseDown()) {
            throw new DataAccessResourceFailureException("database unavailable");
        }
    }
}
