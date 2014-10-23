package de.qabel.core.config;

import java.util.Set;
import java.util.HashSet;

public class DropServers {
	/**
	 * <pre>
	 *           1     0..*
	 * DropServers ------------------------- DropServer
	 *           dropServers        &gt;       dropServer
	 * </pre>
	 */
	private Set<DropServer> dropServer;

	public Set<DropServer> getDropServer() {
		if (this.dropServer == null) {
			this.dropServer = new HashSet<DropServer>();
		}
		return this.dropServer;
	}

	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result
				+ ((dropServer == null) ? 0 : dropServer.hashCode());
		return result;
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		DropServers other = (DropServers) obj;
		if (dropServer == null) {
			if (other.dropServer != null)
				return false;
		} else if (!dropServer.equals(other.dropServer))
			return false;
		return true;
	}

	
}
