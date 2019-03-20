package org.coldis.library.test.service.client.dto;

import java.io.Serializable;
import java.util.Objects;
import java.util.Arrays;

/**
 * DtoTestObjectDto.
 */
public class DtoTestObjectDto implements Serializable {

	/**
	 * Serial.
	 */
	private static final long serialVersionUID = 358876200-1877623249L;
	
	/**
	 * id.
	 */
	private java.lang.Long id;

	/**
	 * test1.
	 */
	private org.coldis.library.test.service.client.dto.DtoTestObject2Dto test1;

	/**
	 * test2.
	 */
	private java.util.List<org.coldis.library.test.service.client.dto.DtoTestObject2Dto> test2;

	/**
	 * test3.
	 */
	private java.lang.String test3;

	/**
	 * test4.
	 */
	private org.coldis.library.test.service.client.dto.DtoTestObject2Dto test4;

	/**
	 * test5.
	 */
	private java.lang.String test5;

	/**
	 * test6.
	 */
	private org.coldis.library.test.service.client.dto.DtoTestObject2Dto[] test6;

	/**
	 * test7.
	 */
	private int test7;

	/**
	 * test8.
	 */
	private int[] test88;

	/**
	 * test9.
	 */
	private java.lang.Integer test9;


	/**
	 * No arguments constructor.
	 */
	public DtoTestObjectDto() {
		super();
	}

	/**
	 * Default constructor.
 	 * @param id
 	 *            id.
 	 * @param test1
 	 *            test1.
 	 * @param test2
 	 *            test2.
 	 * @param test3
 	 *            test3.
 	 * @param test4
 	 *            test4.
 	 * @param test5
 	 *            test5.
 	 * @param test6
 	 *            test6.
 	 * @param test7
 	 *            test7.
 	 * @param test88
 	 *            test8.
 	 * @param test9
 	 *            test9.
	 */
	public DtoTestObjectDto(
			java.lang.Long id,
			org.coldis.library.test.service.client.dto.DtoTestObject2Dto test1,
			java.util.List<org.coldis.library.test.service.client.dto.DtoTestObject2Dto> test2,
			java.lang.String test3,
			org.coldis.library.test.service.client.dto.DtoTestObject2Dto test4,
			java.lang.String test5,
			org.coldis.library.test.service.client.dto.DtoTestObject2Dto[] test6,
			int test7,
			int[] test88,
			java.lang.Integer test9) {
		super();
		this.id = id;
		this.test1 = test1;
		this.test2 = test2;
		this.test3 = test3;
		this.test4 = test4;
		this.test5 = test5;
		this.test6 = test6;
		this.test7 = test7;
		this.test88 = test88;
		this.test9 = test9;
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
	 * Gets the test1.
	 * @return The test1.
	 */
	public org.coldis.library.test.service.client.dto.DtoTestObject2Dto getTest1() {
		return test1;
	}
	/**
	 * Sets the test1.
	 *
	 * @param test1
	 *            The test1.
	 */
	public void setTest1(final org.coldis.library.test.service.client.dto.DtoTestObject2Dto test1) {
		this.test1 = test1;
	}
	/**
	 * Gets the test2.
	 * @return The test2.
	 */
	public java.util.List<org.coldis.library.test.service.client.dto.DtoTestObject2Dto> getTest2() {
		return test2;
	}
	/**
	 * Sets the test2.
	 *
	 * @param test2
	 *            The test2.
	 */
	public void setTest2(final java.util.List<org.coldis.library.test.service.client.dto.DtoTestObject2Dto> test2) {
		this.test2 = test2;
	}
	/**
	 * Gets the test3.
	 * @return The test3.
	 */
	public java.lang.String getTest3() {
		return test3;
	}
	/**
	 * Sets the test3.
	 *
	 * @param test3
	 *            The test3.
	 */
	public void setTest3(final java.lang.String test3) {
		this.test3 = test3;
	}
	/**
	 * Gets the test4.
	 * @return The test4.
	 */
	public org.coldis.library.test.service.client.dto.DtoTestObject2Dto getTest4() {
		return test4;
	}
	/**
	 * Sets the test4.
	 *
	 * @param test4
	 *            The test4.
	 */
	public void setTest4(final org.coldis.library.test.service.client.dto.DtoTestObject2Dto test4) {
		this.test4 = test4;
	}
	/**
	 * Gets the test5.
	 * @return The test5.
	 */
	public java.lang.String getTest5() {
		return test5;
	}
	/**
	 * Sets the test5.
	 *
	 * @param test5
	 *            The test5.
	 */
	public void setTest5(final java.lang.String test5) {
		this.test5 = test5;
	}
	/**
	 * Gets the test6.
	 * @return The test6.
	 */
	public org.coldis.library.test.service.client.dto.DtoTestObject2Dto[] getTest6() {
		return test6;
	}
	/**
	 * Sets the test6.
	 *
	 * @param test6
	 *            The test6.
	 */
	public void setTest6(final org.coldis.library.test.service.client.dto.DtoTestObject2Dto[] test6) {
		this.test6 = test6;
	}
	/**
	 * Gets the test7.
	 * @return The test7.
	 */
	public int getTest7() {
		return test7;
	}
	/**
	 * Sets the test7.
	 *
	 * @param test7
	 *            The test7.
	 */
	public void setTest7(final int test7) {
		this.test7 = test7;
	}
	/**
	 * Gets the test8.
	 * @return The test8.
	 */
	public int[] getTest88() {
		return test88;
	}
	/**
	 * Sets the test8.
	 *
	 * @param test88
	 *            The test8.
	 */
	public void setTest88(final int[] test88) {
		this.test88 = test88;
	}
	/**
	 * Gets the test9.
	 * @return The test9.
	 */
	public java.lang.Integer getTest9() {
		return test9;
	}
	/**
	 * Sets the test9.
	 *
	 * @param test9
	 *            The test9.
	 */
	public void setTest9(final java.lang.Integer test9) {
		this.test9 = test9;
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
				test1,
				test2,
				test3,
				test4,
				test5,
				test7
			);
		result = prime * result + Arrays.hashCode(test6);
		result = prime * result + Arrays.hashCode(test88);
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
		final DtoTestObjectDto other = (DtoTestObjectDto) obj;
		if (! Objects.equals(id, other.id)) {
			return false;
		}
		if (! Objects.equals(test1, other.test1)) {
			return false;
		}
		if (! Objects.equals(test2, other.test2)) {
			return false;
		}
		if (! Objects.equals(test3, other.test3)) {
			return false;
		}
		if (! Objects.equals(test4, other.test4)) {
			return false;
		}
		if (! Objects.equals(test5, other.test5)) {
			return false;
		}
		if (! Arrays.equals(test6, other.test6)) {
			return false;
		}
		if (! Objects.equals(test7, other.test7)) {
			return false;
		}
		if (! Arrays.equals(test88, other.test88)) {
			return false;
		}
		return true;
	}
	
}