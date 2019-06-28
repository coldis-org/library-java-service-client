package org.coldis.library.service.client.generator;

import java.io.Serializable;
import java.util.Objects;

/**
 * Service client operation parameter metadata.
 */
public class ServiceClientOperationParameterMetadata implements Serializable {

	/**
	 * Serial.
	 */
	private static final long serialVersionUID = 6528596189706828445L;

	/**
	 * Operation parameter type.
	 */
	private String type;

	/**
	 * Operation parameter original name.
	 */
	private String originalName;

	/**
	 * Operation parameter name.
	 */
	private String name;

	/**
	 * Operation parameter kind.
	 */
	private ServiceOperationParameterKind kind;

	/**
	 * Default constructor.
	 *
	 * @param type         Operation parameter type.
	 * @param originalName Operation parameter original name.
	 * @param name         Operation parameter name.
	 * @param kind         Operation parameter kind.
	 */
	public ServiceClientOperationParameterMetadata(final String type, final String originalName, final String name,
			final ServiceOperationParameterKind kind) {
		super();
		this.type = type;
		this.originalName = originalName;
		this.name = name;
		this.kind = kind;
	}

	/**
	 * Gets the type.
	 *
	 * @return The type.
	 */
	public String getType() {
		return this.type;
	}

	/**
	 * Sets the type.
	 *
	 * @param type New type.
	 */
	public void setType(final String type) {
		this.type = type;
	}

	/**
	 * Gets the originalName.
	 *
	 * @return The originalName.
	 */
	public String getOriginalName() {
		return this.originalName;
	}

	/**
	 * Sets the originalName.
	 *
	 * @param originalName New originalName.
	 */
	public void setOriginalName(final String originalName) {
		this.originalName = originalName;
	}

	/**
	 * Gets the name.
	 *
	 * @return The name.
	 */
	public String getName() {
		return this.name;
	}

	/**
	 * Sets the name.
	 *
	 * @param name New name.
	 */
	public void setName(final String name) {
		this.name = name;
	}

	/**
	 * Gets the kind.
	 *
	 * @return The kind.
	 */
	public ServiceOperationParameterKind getKind() {
		return this.kind;
	}

	/**
	 * Sets the kind.
	 *
	 * @param kind New kind.
	 */
	public void setKind(final ServiceOperationParameterKind kind) {
		this.kind = kind;
	}

	/**
	 * @see java.lang.Object#hashCode()
	 */
	@Override
	public int hashCode() {
		return Objects.hash(this.kind, this.name, this.type);
	}

	/**
	 * @see java.lang.Object#equals(java.lang.Object)
	 */
	@Override
	public boolean equals(final Object obj) {
		if (this == obj) {
			return true;
		}
		if (obj == null) {
			return false;
		}
		if (!(obj instanceof ServiceClientOperationParameterMetadata)) {
			return false;
		}
		final ServiceClientOperationParameterMetadata other = (ServiceClientOperationParameterMetadata) obj;
		return Objects.equals(this.kind, other.kind) && Objects.equals(this.name, other.name)
				&& Objects.equals(this.type, other.type);
	}

}