package it.filippor.jdt.bsp.core.protocol;

import java.util.List;
import ch.epfl.scala.bsp4j.BuildTargetIdentifier;

public class BspTargetParams {

	private final List<BuildTargetIdentifier> targets;

	public BspTargetParams(List<BuildTargetIdentifier> targets) {
		this.targets = targets;
	}

	public List<BuildTargetIdentifier> getTargets() {
		return this.targets;
	}
}
