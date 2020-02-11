package org.coldis.library.service.model;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * Page of objects.
 *
 * @param <ObjectType> Object type.
 */
public class Page<ObjectType> implements Serializable {

	/**
	 * Serial.
	 */
	private static final long serialVersionUID = 6446414558737364910L;

	/**
	 * Page number.
	 */
	private Integer number;

	/**
	 * Page size.
	 */
	private Integer size;

	/**
	 * Total number of objects (including the ones not in the page).
	 */
	private Integer totalElements;

	/**
	 * Objects in the page.
	 */
	private List<ObjectType> content;

	/**
	 * Gets the number.
	 *
	 * @return The number.
	 */
	public Integer getNumber() {
		return this.number;
	}

	/**
	 * Sets the number.
	 *
	 * @param number New number.
	 */
	public void setNumber(final Integer number) {
		this.number = number;
	}

	/**
	 * Gets the size.
	 *
	 * @return The size.
	 */
	public Integer getSize() {
		return this.size;
	}

	/**
	 * Sets the size.
	 *
	 * @param size New size.
	 */
	public void setSize(final Integer size) {
		this.size = size;
	}

	/**
	 * Gets the totalElements.
	 *
	 * @return The totalElements.
	 */
	public Integer getTotalElements() {
		return this.totalElements;
	}

	/**
	 * Sets the totalElements.
	 *
	 * @param totalElements New totalElements.
	 */
	public void setTotalElements(final Integer totalElements) {
		this.totalElements = totalElements;
	}

	/**
	 * Gets the content.
	 *
	 * @return The content.
	 */
	public List<ObjectType> getContent() {
		// Makes sure the list is initialized.
		this.content = this.content == null ? new ArrayList<>() : this.content;
		// Returns the list.
		return this.content;
	}

	/**
	 * Sets the content.
	 *
	 * @param content New content.
	 */
	public void setContent(final List<ObjectType> content) {
		this.content = content;
	}

}
