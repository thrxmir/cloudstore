package co.codewizards.cloudstore.core.dto;

import javax.xml.bind.annotation.XmlRootElement;

@XmlRootElement
public class CopyModificationDto extends ModificationDto {

	private String fromPath;

	private String toPath;

	public String getFromPath() {
		return fromPath;
	}
	public void setFromPath(String fromPath) {
		this.fromPath = fromPath;
	}

	public String getToPath() {
		return toPath;
	}
	public void setToPath(String toPath) {
		this.toPath = toPath;
	}

	@Override
	public String toString() {
		return getClass().getSimpleName() + '[' + toString_getProperties() + ']';
	}

	protected String toString_getProperties() {
		return "id=" + getId()
				+ ", localRevision=" + getLocalRevision()
				+ ", fromPath=" + fromPath
				+ ", toPath=" + toPath;
	}
}
