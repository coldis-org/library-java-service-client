package org.coldis.library.service.client.generator;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Service client operation metadata.
 */
public class ServiceClientOperationMetadata implements Serializable {

	/**
	 * Serial.
	 */
	private static final long serialVersionUID = 6528596189706828445L;

	/**
	 * Operation name.
	 */
	private String name;

	/**
	 * Operation documentation comment.
	 */
	private String docComment;

	/**
	 * Operation path.
	 */
	private String path;

	/**
	 * Operation method.
	 */
	private String method;

	/**
	 * Operation media type.
	 */
	private String mediaType;

	/**
	 * Operation return type.
	 */
	private String returnType;

	/**
	 * Operation parameters.
	 */
	private List<ServiceClientOperationParameterMetadata> parameters;

	/**
	 * Default constructor.
	 *
	 * @param name       Operation name.
	 * @param docComment Operation documentation comment.
	 * @param path       Operation path.
	 * @param method     Operation method.
	 * @param mediaType  Operation media type.
	 * @param returnType Operation return type.
	 * @param parameters Operation parameters.
	 */
	public ServiceClientOperationMetadata(final String name, final String docComment, final String path,
			final String method, final String mediaType, final String returnType,
			final List<ServiceClientOperationParameterMetadata> parameters) {
		super();
		this.name = name;
		this.docComment = docComment;
		this.path = path;
		this.method = method;
		this.mediaType = mediaType;
		this.returnType = returnType;
		this.parameters = parameters;
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
	 * Gets the docComment.
	 *
	 * @return The docComment.
	 */
	public String getDocComment() {
		return this.docComment;
	}

	/**
	 * Sets the docComment.
	 *
	 * @param docComment New docComment.
	 */
	public void setDocComment(final String docComment) {
		this.docComment = docComment;
	}

	/**
	 * Gets the path.
	 *
	 * @return The path.
	 */
	public String getPath() {
		return this.path;
	}

	/**
	 * Sets the path.
	 *
	 * @param path New path.
	 */
	public void setPath(final String path) {
		this.path = path;
	}

	/**
	 * Gets the method.
	 *
	 * @return The method.
	 */
	public String getMethod() {
		return this.method;
	}

	/**
	 * Sets the method.
	 *
	 * @param method New method.
	 */
	public void setMethod(final String method) {
		this.method = method;
	}

	/**
	 * Gets the mediaType.
	 *
	 * @return The mediaType.
	 */
	public String getMediaType() {
		return this.mediaType;
	}

	/**
	 * Sets the mediaType.
	 *
	 * @param mediaType New mediaType.
	 */
	public void setMediaType(final String mediaType) {
		this.mediaType = mediaType;
	}

	/**
	 * Gets the returnType.
	 *
	 * @return The returnType.
	 */
	public String getReturnType() {
		return this.returnType;
	}

	/**
	 * Sets the returnType.
	 *
	 * @param returnType New returnType.
	 */
	public void setReturnType(final String returnType) {
		this.returnType = returnType;
	}

	/**
	 * Gets the parameters.
	 *
	 * @return The parameters.
	 */
	public List<ServiceClientOperationParameterMetadata> getParameters() {
		// If the list has not been initialized.
		if (this.parameters == null) {
			// Initializes the list.
			this.parameters = new ArrayList<>();
		}
		// Returns the list.
		return this.parameters;
	}

	/**
	 * Sets the parameters.
	 *
	 * @param parameters New parameters.
	 */
	public void setParameters(final List<ServiceClientOperationParameterMetadata> parameters) {
		this.parameters = parameters;
	}

	/**
	 * @see java.lang.Object#hashCode()
	 */
	@Override
	public int hashCode() {
		return Objects.hash(this.docComment, this.mediaType, this.method, this.name, this.parameters, this.path,
				this.returnType);
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
		if (!(obj instanceof ServiceClientOperationMetadata)) {
			return false;
		}
		final ServiceClientOperationMetadata other = (ServiceClientOperationMetadata) obj;
		return Objects.equals(this.docComment, other.docComment) && Objects.equals(this.mediaType, other.mediaType)
				&& Objects.equals(this.method, other.method) && Objects.equals(this.name, other.name)
				&& Objects.equals(this.parameters, other.parameters) && Objects.equals(this.path, other.path)
				&& Objects.equals(this.returnType, other.returnType);
	}

}