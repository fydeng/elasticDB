package com.bittiger.client;

import java.util.Random;
import java.util.concurrent.BlockingQueue;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.bittiger.logic.ActionType;
import com.bittiger.logic.LoadBalancer;
import com.bittiger.logic.Server;
import com.bittiger.querypool.QueryMetaData;
import com.bittiger.client.Utilities;

public class UserSession extends Thread {
	private TPCWProperties tpcw = null;
	private ClientEmulator client = null;
	private Random rand = null;
	private boolean suspendThread = false;
	private BlockingQueue<Integer> queue;
	private int id;

	private Connection writeConn;

	private static transient final Logger LOG = LoggerFactory
			.getLogger(UserSession.class);

	public UserSession(int id, ClientEmulator client,
			BlockingQueue<Integer> bQueue) {
		super("UserSession" + id);
		this.id = id;
		this.queue = bQueue;
		this.client = client;
		this.tpcw = client.getTpcw();
		this.rand = new Random();
	}

	private long TPCWthinkTime(double mean) {
		double r = rand.nextDouble();
		return ((long) (((0 - mean) * Math.log(r))));
	}

	public synchronized void notifyThread() {
		notify();
	}

	public synchronized void releaseThread() {
		suspendThread = false;
	}

	public synchronized void holdThread() {
		suspendThread = true;
	}
	

	private String computeNextSql(double rwratio, double[] read, double[] write) {
		String sql = "";
		// first decide read or write
		double rw = rand.nextDouble();
		if (rw < rwratio) {
			sql += "bq";
			double internal = rand.nextDouble();
			int num = 0;
			for (int i = 0; i < read.length - 1; i++) {
				if (read[i] < internal && internal <= read[i + 1]) {
					num = i + 1;
					sql += num;
					break;
				}
			}

		} else {
			sql += "wq";
			double internal = rand.nextDouble();
			int num = 0;
			for (int i = 0; i < write.length - 1; i++) {
				if (write[i] < internal && internal <= write[i + 1]) {
					num = i + 1;
					sql += num;
					break;
				}
			}
		}
		return sql;
	}

	private Connection getNextConnection(String sql) {
		// read
		if (sql.contains("b")) {
			return getNextReadConnection(client.getLoadBalancer());
		} else {
			if (writeConn == null) {
				writeConn = getNextWriteConnection(client.getLoadBalancer());
			}
			return writeConn;
		}
	}

	public Connection getNextWriteConnection(LoadBalancer loadBalancer) {
		Server server = null;
		Connection connection = null;
		try {
			Class.forName("com.mysql.jdbc.Driver").newInstance();
			// DriverManager.setLoginTimeout(5);
			server = loadBalancer.getWriteQueue();
			connection = (Connection) DriverManager.getConnection(
					Utilities.getUrl(server), client.getTpcw().username,
					client.getTpcw().password);
			connection.setAutoCommit(true);
		} catch (Exception e) {
			LOG.error(e.toString());
		}
		LOG.debug("choose write server as " + server.getIp());
		return connection;
	}

	public Connection getNextReadConnection(LoadBalancer loadBalancer) {
		try {
			Class.forName("com.mysql.jdbc.Driver").newInstance();
			Server server = loadBalancer.getNextReadServer();
			Connection connection = (Connection) DriverManager.getConnection(
					Utilities.getUrl(server), client.getTpcw().username,
					client.getTpcw().password);
			connection.setAutoCommit(true);
			return connection;
		} catch (Exception ex) {
			throw new RuntimeException(ex);
		}
	}

	public void run() {
		while (!client.isEndOfSimulation()) {
			try {
				synchronized (this) {
					while (suspendThread)
						wait();
				}
				// decide of closed or open system
				double r = rand.nextDouble();
				if (r < tpcw.mixRate) {
					int t = queue.take();
					LOG.debug(t + " has been taken");
				} else {
					Thread.sleep((long) ((float) TPCWthinkTime(tpcw.TPCmean)));
				}

				String queryclass = computeNextSql(tpcw.rwratio, tpcw.read,
						tpcw.write);
				Connection connection = getNextConnection(queryclass);
				String classname = "com.bittiger.querypool." + queryclass;
				//factory pattern: initialize an object from a string
				QueryMetaData query = (QueryMetaData) Class.forName(classname)
						.newInstance();
				String command = query.getQueryStr();

				Statement stmt = connection.createStatement();
				if (queryclass.contains("b")) {
					long start = System.currentTimeMillis();
					stmt.executeQuery(command);
					long end = System.currentTimeMillis();
					client.getMonitor().addQuery(this.id, queryclass, start,
							end);
					stmt.close();
					connection.close();
				} else {
					long start = System.currentTimeMillis();
					stmt.executeUpdate(command);
					long end = System.currentTimeMillis();
					client.getMonitor().addQuery(this.id, queryclass, start,
							end);
					stmt.close();
				}
			} catch (Exception ex) {
				client.increaseFailCount();
				if (client.getFailCount() >= Utilities.retryTimes) {
					LOG.debug("no connection for " + Utilities.retryTimes + " times, fire Availability rule");
					client.getEventQueue().put(ActionType.AvailNotEnoughAddServer);
					Server s = client.getLoadBalancer().removeDestroyedServer();
					LOG.debug("Availability not enough rule has been fired, destroyed server " + s.getIp() + " is removed from load balancer");
				}
				LOG.error("Error while running session: " + ex.getMessage());
			}
		}
		try {
			if (writeConn != null) {
				writeConn.close();
			}
		} catch (SQLException ex) {
			LOG.error("Error while close connection " + ex.getMessage());
		}
		client.increaseThread();
	}
}
