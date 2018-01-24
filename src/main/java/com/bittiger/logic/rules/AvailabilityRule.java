package com.bittiger.logic.rules;

import org.easyrules.annotation.Action;
import org.easyrules.annotation.Condition;
import org.easyrules.annotation.Rule;

import com.bittiger.client.ClientEmulator;
import com.bittiger.logic.ActionType;
import com.bittiger.client.Utilities;


@Rule(name = "AvailabilityRule", description = "Check if we need to add server for availability")
public class AvailabilityRule {

	int failCount;
	int retryTimes;
	ClientEmulator ce;

	@Condition
	public boolean checkPerformance() {
		return failCount >= retryTimes;
	}

	@Action
	public void addServer() throws Exception {
		ce.getEventQueue().put(ActionType.AvailNotEnoughAddServer);
	}

	public void setInput(ClientEmulator c) {
		this.ce = c;
		this.failCount = c.getFailCount();
		this.retryTimes = Utilities.retryTimes;
	}
}