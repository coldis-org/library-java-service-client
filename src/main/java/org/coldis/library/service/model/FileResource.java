package org.coldis.library.service.model;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;

import org.springframework.core.io.AbstractResource;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * File resource.
 */
public class FileResource extends AbstractResource {

	/**
	 * File name.
	 */
	private String name;

	/**
	 * File content.
	 */
	private byte[] content;

	/**
	 * File description.
	 */
	private String description;

	/**
	 * No arguments constructor.
	 */
	public FileResource() {
	}

	/**
	 * Name, content and description.
	 *
	 * @param name        The file name.
	 * @param content     The file content.
	 * @param description The file description.
	 */
	public FileResource(final String name, final byte[] content, final String description) {
		this.name = name;
		this.content = content;
		this.description = description;
	}

	/**
	 * Name and content constructor.
	 *
	 * @param name    The file name.
	 * @param content The file content.
	 */
	public FileResource(final String name, final byte[] content) {
		this(name, content, null);
	}

	/**
	 * @see org.springframework.core.io.AbstractResource#getFilename()
	 */
	@Override
	public String getFilename() {
		return this.name;
	}

	/**
	 * Sets the file name.
	 *
	 * @param name New file name.
	 */
	public void setFilename(final String name) {
		this.name = name;
	}

	/**
	 * Return the underlying content.
	 *
	 * @return The underlying content.
	 */
	public final byte[] getContent() {
		return this.content;
	}

	/**
	 * Sets the content.
	 *
	 * @param content New content.
	 */
	public void setContent(final byte[] content) {
		this.content = content;
	}

	/**
	 * This implementation returns a description that includes the passed-in
	 * {@code description}, if any.
	 */
	@Override
	public String getDescription() {
		return this.description;
	}

	/**
	 * Sets the description.
	 *
	 * @param description New description.
	 */
	public void setDescription(final String description) {
		this.description = description;
	}

	/**
	 * This implementation always returns {@code true}.
	 */
	@Override
	@JsonIgnore
	public boolean exists() {
		return true;
	}

	/**
	 * This implementation returns the length of the underlying content.
	 */
	@Override
	@JsonIgnore
	public long contentLength() {
		return this.getContent().length;
	}

	/**
	 * @see org.springframework.core.io.AbstractResource#isOpen()
	 */
	@Override
	@JsonIgnore
	public boolean isOpen() {
		return super.isOpen();
	}

	/**
	 * @see org.springframework.core.io.AbstractResource#isReadable()
	 */
	@Override
	@JsonIgnore
	public boolean isReadable() {
		return super.isReadable();
	}

	/**
	 * This implementation returns a ContentInputStream for the underlying content.
	 *
	 * @see java.io.ContentInputStream
	 */
	@Override
	@JsonIgnore
	public InputStream getInputStream() throws IOException {
		return new ByteArrayInputStream(this.getContent());
	}

	/**
	 * @see org.springframework.core.io.AbstractResource#getURL()
	 */
	@Override
	@JsonIgnore
	public URL getURL() throws IOException {
		return super.getURL();
	}

	/**
	 * @see org.springframework.core.io.AbstractResource#getURI()
	 */
	@Override
	@JsonIgnore
	public URI getURI() throws IOException {
		return super.getURI();
	}

	/**
	 * @see org.springframework.core.io.AbstractResource#getFile()
	 */
	@Override
	@JsonIgnore
	public File getFile() throws IOException {
		return super.getFile();
	}

	/**
	 * @see org.springframework.core.io.AbstractResource#getFileForLastModifiedCheck()
	 */
	@Override
	@JsonIgnore
	protected File getFileForLastModifiedCheck() throws IOException {
		return super.getFileForLastModifiedCheck();
	}

}
