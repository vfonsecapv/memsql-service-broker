package org.cf.cloud.servicebroker.memsql.service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import org.cf.cloud.servicebroker.memsql.exception.MemSQLServiceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Utility class for manipulating a MemSQL database.
 *
 *
 */



@Service
public class MemSQLAdminService {

	public static final String ADMIN_DB = "admin";
	public static final String MEMQSQL_DB_PREFIX = "memsqldb";
	public static final String MEMQSQL_USER_PREFIX = "memsqluser";
	
	private static boolean isRunningEnterprise = true;
	
	private Logger logger = LoggerFactory.getLogger(MemSQLAdminService.class);
	
	private MemSQLClient client;
	
	@Autowired
	public MemSQLAdminService(MemSQLClient client) {
		this.client = client;
	}
	
	public boolean databaseExists(String databaseName) throws MemSQLServiceException {
		String dbName = formatDbName(databaseName);
		
		try {
			Connection connection = client.getConnection();
			ResultSet res = connection.getMetaData().getCatalogs();

			while (res.next()){

				String dbName2 = res.getString(1);
				if(dbName2.equals(dbName)){
					return true;
				}
			}
			return false;
		} catch (SQLException e) {
			throw handleException(e);
		}
	}


	public boolean userExists(String username) throws MemSQLServiceException{

		try {
			Connection connection = client.getConnection();
			Statement stmt = connection.createStatement();
			String user = formatUserName(username);
			ResultSet res = stmt.executeQuery("SELECT DISTINCT GRANTEE FROM information_schema.USER_PRIVILEGES WHERE GRANTEE LIKE '\\'"+user+"\\'%'");
			while(res.next()){

				String uNameFull = res.getString(1);
				String[] uNameList = uNameFull.split("@");

				String uName = uNameList[0];

				user = "'"+user+"'";
				if (uName.equals(user)){
					return true;
				}
			}
			return false;
		} catch (SQLException e) {
			throw handleException(e);
		}
	}
	
	public void deleteDatabase(String databaseName) throws MemSQLServiceException {
		String dbName = formatDbName(databaseName);
		try {
			Connection connection = client.getConnection();
			Statement stmt = connection.createStatement();
			stmt.executeUpdate("DROP DATABASE " + dbName);
		} catch (SQLException e) {
			throw handleException(e);
		}
	}
	
	public String createDatabase(String databaseName) throws MemSQLServiceException {
		Statement stmt = null;
		String dbName = formatDbName(databaseName);
		try {
			Connection connection = client.getConnection();
			stmt = connection.createStatement();
			logger.info("Attempting to create db using statement: CREATE DATABASE IF NOT EXISTS " + dbName);
			stmt.executeUpdate("CREATE DATABASE IF NOT EXISTS " + dbName);
			return databaseName;
		
		} catch (SQLException sqle) {
			sqle.printStackTrace(); 
			throw handleException(sqle);
		}
	}


	public DatabaseCredentials createUser(String database, String username, String password) throws MemSQLServiceException, SQLException {
		String dbName = formatDbName(database);
		
		// Commented portion would only work against MemSQL Enterprise edition
		//String psql = "GRANT all ON " + dbName + ".* TO ? IDENTIFIED BY ?";
		
		// use this in the interim which would provide an user created with all access to all databases
		String createDBForCommunity = "GRANT all ON *.* TO ? IDENTIFIED BY ?";
		String createDBForEnterprise = "GRANT all ON " + dbName + ".* TO ? IDENTIFIED BY ?";
		String user = formatUserName(username);
		
		DatabaseCredentials dbCredentials = new DatabaseCredentials();
		dbCredentials.setDatabaseName(dbName);
		dbCredentials.setUsername(user);
		dbCredentials.setPassword(password);
		dbCredentials.setHost(this.client.getHost());
		dbCredentials.setPort(this.client.getPort());
		
		PreparedStatement pstmt = null;
		Connection connection = null;
		try {
			connection = client.getConnection();
			
			if (isRunningEnterprise) {
				pstmt = connection.prepareStatement(createDBForEnterprise);
				logger.info("Attempting to create DB User in Enterprise Edition mode");
			} else {
				pstmt = connection.prepareStatement(createDBForCommunity);
				logger.info("Running on Community Edition - all valid users would be able to access all databases");
			}
			
			pstmt.setString(1, user);
			pstmt.setString(2, password);
			
			try {
				pstmt.executeUpdate();
			} catch(SQLException sqle) {
				
				if (isRunningEnterprise) {
					isRunningEnterprise = false;
					
					cleanup(pstmt);
					pstmt = connection.prepareStatement(createDBForCommunity);
					logger.error("WARNING!! Server not running in Enterprise Edition but in Community Edition!!");
					logger.error("\t All users generated would be able to access all databases in Community Edition!!");
					
					pstmt.setString(1, user);
					pstmt.setString(2, password);
					
					pstmt.executeUpdate();
				}				
			}
			
			return dbCredentials;
		}catch (SQLException e) {
			e.printStackTrace();
			try {
				deleteUser(database, user);
			} catch (MemSQLServiceException ignore) {}
			throw handleException(e);
		} finally {
			cleanup(pstmt);
		}
	}

	public void cleanup(Statement stmt) {
		try {
			if (stmt != null)
				stmt.close();
		} catch(Exception e) {
			stmt = null;
		}
	}

	public void deleteUser(String database, String username) throws MemSQLServiceException {
		try {
			Connection connection = client.getConnection();
			Statement stmt = connection.createStatement();
			stmt.executeUpdate("DROP USER "+ formatUserName(username));

		}catch (SQLException e) {
			throw handleException(e);
		}

	}

	private MemSQLServiceException handleException(Exception e) {
		logger.warn(e.getLocalizedMessage(), e);
		return new MemSQLServiceException(e.getLocalizedMessage());
	}
	
	private static String formatDbName(String name) {
		return MEMQSQL_DB_PREFIX + shortenName(name, 32);
	}
	
	private static String formatUserName(String name) {
		return MEMQSQL_USER_PREFIX + shortenName(name, 22);
	}
	
	private static String shortenName(String name, int len) {
		String newName = name.replaceAll("-", "").replaceAll("_", "");
		return (newName.length() <= len) ?  newName : newName.substring(0,  len);
	}

}
