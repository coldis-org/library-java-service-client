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
	private static final long serialVersionUID = 3588762001922639325L;
	
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
	 * Default constructor.
 	 * @param id
 	 *            id.
 	 * @param test
 	 *            test.
	 */
	public DtoTestObject2Dto(
			java.lang.Long id,
			java.lang.String test) {
		super();
		this.id = id;
		this.test = test;
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