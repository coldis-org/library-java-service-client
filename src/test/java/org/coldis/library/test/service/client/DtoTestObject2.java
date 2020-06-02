package org.coldis.library.test.service.client;

import org.coldis.library.dto.DtoType;
import org.coldis.library.model.Identifiable;

/**
 * DTO test object.
 */
@DtoType(targetPath = "src/test/java", namespace = "org.coldis.library.test.service.client.dto")
public class DtoTestObject2 implements Identifiable {

	/**
	 * Serial.
	 */
	private static final long serialVersionUID = -6904605762253009838L;

	/**
	 * Object identifier.
	 */
	private Long id;

	/**
	 * Test attribute.
	 */
	private String test;

	/**
	 * @see org.coldis.library.model.Identifiable#getId()
	 */
	@Override
	public Long getId() {
		return this.id;
	}

	/**
	 * Sets the identifier.
	 *
	 * @param id New identifier.
	 */
	public void setId(final Long id) {
		this.id = id;
	}

	/**
	 * Gets the test.
	 *
	 * @return The test.
	 */
	public String getTest() {
		return this.test;
	}

	/**
	 * Sets the test.
	 *
	 * @param test New test.
	 */
	public void setTest(final String test) {
		this.test = test;
	}

	/**
	 * @see java.lang.Object#hashCode()
	 */
	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = (prime * result) + ((this.id == null) ? 0 : this.id.hashCode());
		result = (prime * result) + ((this.test == null) ? 0 : this.test.hashCode());
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
		if (this.getClass() != obj.getClass()) {
			return false;
		}
		final DtoTestObject2 other = (DtoTestObject2) obj;
		if (this.id == null) {
			if (other.id != null) {
				return false;
			}
		}
		else if (!this.id.equals(other.id)) {
			return false;
		}
		if (this.test == null) {
			if (other.test != null) {
				return false;
			}
		}
		else if (!this.test.equals(other.test)) {
			return false;
		}
		return true;
	}

}
