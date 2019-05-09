package org.coldis.library.test.service.client.dto;

import java.io.Serializable;
import java.util.Objects;
import java.util.Arrays;

/**
 * DtoTestObject2Dto.
 */
public class DtoTestObject2Dto implements Serializable {

	/**
	 * Serial.
	 */
	private static final long serialVersionUID = 1922639325L;
	
	/**
	 * id.
	 */
	private java.lang.Long id;

	/**
	 * test.
	 */
	private java.lang.String test;


	/**
	 * No arguments constructor.
	 */
	public DtoTestObject2Dto() {
		super();
	}

	/**
	 * Gets the id.
	 * @return The id.
	 */
	public java.lang.Long getId() {
		return id;
	}
	/**
	 * Sets the id.
	 *
	 * @param id
	 *            The id.
	 */
	public void setId(final java.lang.Long id) {
		this.id = id;
	}
	
	/**
	 * Sets the id and returns the updated object.
	 *
	 * @param id
	 *            The id.
	 * @return The updated object.
	 */
	public DtoTestObject2Dto withId(final java.lang.Long id) {
		this.setId(id);
		return this;
	}
	/**
	 * Gets the test.
	 * @return The test.
	 */
	public java.lang.String getTest() {
		return test;
	}
	/**
	 * Sets the test.
	 *
	 * @param test
	 *            The test.
	 */
	public void setTest(final java.lang.String test) {
		this.test = test;
	}
	
	/**
	 * Sets the test and returns the updated object.
	 *
	 * @param test
	 *            The test.
	 * @return The updated object.
	 */
	public DtoTestObject2Dto withTest(final java.lang.String test) {
		this.setTest(test);
		return this;
	}

	/**
	 * @see java.lang.Object#hashCode()
	 */
	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result + Objects.hash(
				id,
				test
			);
		return result;
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
		if (getClass() != obj.getClass()) {
			return false;
		}
		final DtoTestObject2Dto other = (DtoTestObject2Dto) obj;
		if (! Objects.equals(id, other.id)) {
			return false;
		}
		if (! Objects.equals(test, other.test)) {
			return false;
		}
		return true;
	}
	
}