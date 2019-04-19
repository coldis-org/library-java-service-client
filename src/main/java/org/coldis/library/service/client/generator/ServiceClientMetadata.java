package org.coldis.library.service.client.generator;

import java.io.File;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.apache.commons.lang3.StringUtils;

/**
 * Service client metadata.
 */
public class ServiceClientMetadata implements Serializable {

	/**
	 * Generated serial.
	 */
	private static final long serialVersionUID = 2040660554906895256L;

	/**
	 * Service client context.
	 */
	private String context;

	/**
	 * Target path.
	 */
	private String targetPath;

	/**
	 * Template path.
	 */
	private String templatePath;

	/**
	 * The service client file extension.
	 */
	private String fileExtension;

	/**
	 * Service client namespace.
	 */
	private String namespace;

	/**
	 * Service client super class.
	 */
	private String superclass;

	/**
	 * Service client name.
	 */
	private String name;

	/**
	 * Service client docComment.
	 */
	private String docComment;

	/**
	 * Service client endpoint.
	 */
	private String endpoint;

	/**
	 * Service client operations metadata.
	 */
	private List<ServiceClientOperationMetadata> operations;

	/**
	 * Default constructor.
	 *
	 * @param context       Service client context.
	 * @param targetPath    Target path.
	 * @param templatePath  Template path.
	 * @param fileExtension The service client file extension.
	 * @param namespace     Service client namespace.
	 * @param superclass    Service client superclass.
	 * @param name          Service client name.
	 * @param docComment    Service client docComment.
	 * @param endpoint      Service client endpoint.
	 * @param operations    Service client operations metadata.
	 */
	public ServiceClientMetadata(final String context, final String targetPath, final String templatePath,
			final String fileExtension, final String namespace, final String superclass, final String name,
			final String docComment, final String endpoint, final List<ServiceClientOperationMetadata> operations) {
		super();
		this.context = context;
		this.targetPath = targetPath;
		this.templatePath = templatePath;
		this.fileExtension = fileExtension;
		this.namespace = namespace;
		this.superclass = superclass;
		this.name = name;
		this.docComment = docComment;
		this.endpoint = endpoint;
		this.operations = operations;
	}

	/**
	 * Gets the context.
	 *
	 * @return The context.
	 */
	public String getContext() {
		return this.context;
	}

	/**
	 * Sets the context.
	 *
	 * @param context New context.
	 */
	public void setContext(final String context) {
		this.context = context;
	}

	/**
	 * Gets the targetPath.
	 *
	 * @return The targetPath.
	 */
	public String getTargetPath() {
		return this.targetPath;
	}

	/**
	 * Sets the targetPath.
	 *
	 * @param targetPath New targetPath.
	 */
	public void setTargetPath(final String targetPath) {
		this.targetPath = targetPath;
	}

	/**
	 * Gets the templatePath.
	 *
	 * @return The templatePath.
	 */
	public String getTemplatePath() {
		return this.templatePath;
	}

	/**
	 * Sets the templatePath.
	 *
	 * @param templatePath New templatePath.
	 */
	public void setTemplatePath(final String templatePath) {
		this.templatePath = templatePath;
	}

	/**
	 * Gets the fileExtension.
	 *
	 * @return The fileExtension.
	 */
	public String getFileExtension() {
		return this.fileExtension;
	}

	/**
	 * Sets the fileExtension.
	 *
	 * @param fileExtension New fileExtension.
	 */
	public void setFileExtension(final String fileExtension) {
		this.fileExtension = fileExtension;
	}

	/**
	 * Gets the namespace.
	 *
	 * @return The namespace.
	 */
	public String getNamespace() {
		return this.namespace;
	}

	/**
	 * Sets the namespace.
	 *
	 * @param namespace New namespace.
	 */
	public void setNamespace(final String namespace) {
		this.namespace = namespace;
	}

	/**
	 * Gets the file namespace.
	 *
	 * @return The file namespace.
	 */
	public String getFileNamespace() {
		return this.namespace.replace(".", File.separator);
	}

	/**
	 * Gets the superclass.
	 *
	 * @return The superclass.
	 */
	public String getSuperclass() {
		return this.superclass;
	}

	/**
	 * Sets the superclass.
	 *
	 * @param superclass New superclass.
	 */
	public void setSuperclass(final String superclass) {
		this.superclass = superclass;
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
		return StringUtils.isEmpty(this.docComment) ? this.getName() : this.docComment;
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
	 * Gets the endpoint.
	 *
	 * @return The endpoint.
	 */
	public String getEndpoint() {
		return this.endpoint;
	}

	/**
	 * Sets the endpoint.
	 *
	 * @param endpoint New endpoint.
	 */
	public void setEndpoint(final String endpoint) {
		this.endpoint = endpoint;
	}

	/**
	 * Gets the operations.
	 *
	 * @return The operations.
	 */
	public List<ServiceClientOperationMetadata> getOperations() {
		// If list is not initialized.
		if (this.operations == null) {
			// Initializes it as an empty list.
			this.operations = new ArrayList<>();
		}
		// Returns the list.
		return this.operations;
	}

	/**
	 * Sets the operations.
	 *
	 * @param operations New operations.
	 */
	public void setOperations(final List<ServiceClientOperationMetadata> operations) {
		this.operations = operations;
	}

	/**
	 * @see java.lang.Object#hashCode()
	 */
	@Override
	public int hashCode() {
		return Objects.hash(this.context, this.docComment, this.endpoint, this.fileExtension, this.name, this.namespace,
				this.operations, this.targetPath, this.superclass, this.templatePath);
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
		if (!(obj instanceof ServiceClientMetadata)) {
			return false;
		}
		final ServiceClientMetadata other = (ServiceClientMetadata) obj;
		return Objects.equals(this.context, other.context) && Objects.equals(this.docComment, other.docComment)
				&& Objects.equals(this.endpoint, other.endpoint)
				&& Objects.equals(this.fileExtension, other.fileExtension) && Objects.equals(this.name, other.name)
				&& Objects.equals(this.namespace, other.namespace) && Objects.equals(this.operations, other.operations)
				&& Objects.equals(this.targetPath, other.targetPath)
				&& Objects.equals(this.superclass, other.superclass)
				&& Objects.equals(this.templatePath, other.templatePath);
	}

}
