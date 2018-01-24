package com.bittiger.logic;

import java.util.Date;
import java.util.TimerTask;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.bittiger.client.ClientEmulator;
import com.bittiger.client.Utilities;

public class Destroyer extends TimerTask {
	private ClientEmulator c;
	private String destroyTarget;
	private static transient final Logger LOG = LoggerFactory
			.getLogger(Destroyer.class);

	public Destroyer(ClientEmulator ce) {
		this.c = ce;
		destroyTarget = ce.getTpcw().destroyTarget;
	}

	public void run() {
		Date date = new Date();
		LOG.info("Destroyer is running at " + date.toString());
		try {
			LOG.info("starting destroy server " + destroyTarget);
			Utilities.scaleIn(this.destroyTarget);
		} catch (Exception e) {
			LOG.error(e.getMessage());
		}
	}
}