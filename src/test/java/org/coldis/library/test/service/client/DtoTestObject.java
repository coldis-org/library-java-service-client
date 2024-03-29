package org.coldis.library.test.service.client;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import org.coldis.library.dto.DtoAttribute;
import org.coldis.library.dto.DtoType;
import org.coldis.library.model.Identifiable;

import com.fasterxml.jackson.annotation.JsonGetter;

/**
 * DTO test object.
 */
@DtoType(
		targetPath = "src/test/java",
		namespace = "org.coldis.library.test.service.client.dto"
)
public class DtoTestObject implements Identifiable {

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
	private DtoTestObject2 test1;

	/**
	 * Test attribute.
	 */
	private List<DtoTestObject2> test2;

	/**
	 * Test attribute.
	 */
	private String test3;

	/**
	 * Test attribute.
	 */
	private DtoTestObject2 test4;

	/**
	 * Test attribute.
	 */
	private String test5;

	/**
	 * Test attribute.
	 */
	private DtoTestObject2[] test6;

	/**
	 * Test attribute.
	 */
	private int test7;

	/**
	 * Test attribute.
	 */
	private int[] test8;

	/**
	 * Test attribute.
	 */
	private Integer test9;

	/**
	 * Test attribute.
	 */
	private List<Integer> test10;

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
	public void setId(
			final Long id) {
		this.id = id;
	}

	/**
	 * Gets the test1.
	 *
	 * @return The test1.
	 */
	public DtoTestObject2 getTest1() {
		return this.test1;
	}

	/**
	 * Sets the test1.
	 *
	 * @param test1 New test1.
	 */
	public void setTest1(
			final DtoTestObject2 test1) {
		this.test1 = test1;
	}

	/**
	 * Gets the test2.
	 *
	 * @return The test2.
	 */
	public List<DtoTestObject2> getTest2() {
		return this.test2;
	}

	/**
	 * Sets the test2.
	 *
	 * @param test2 New test2.
	 */
	public void setTest2(
			final List<DtoTestObject2> test2) {
		this.test2 = test2;
	}

	/**
	 * Gets the test3.
	 *
	 * @return The test3.
	 */
	public String getTest3() {
		return this.test3;
	}

	/**
	 * Sets the test3.
	 *
	 * @param test3 New test3.
	 */
	public void setTest3(
			final String test3) {
		this.test3 = test3;
	}

	/**
	 * Gets the test4.
	 *
	 * @return The test4.
	 */
	public DtoTestObject2 getTest4() {
		return this.test4;
	}

	/**
	 * Sets the test4.
	 *
	 * @param test4 New test4.
	 */
	public void setTest4(
			final DtoTestObject2 test4) {
		this.test4 = test4;
	}

	/**
	 * Gets the test5.
	 *
	 * @return The test5.
	 */
	public String getTest5() {
		return this.test5;
	}

	/**
	 * Sets the test5.
	 *
	 * @param test5 New test5.
	 */
	public void setTest5(
			final String test5) {
		this.test5 = test5;
	}

	/**
	 * Gets the test6.
	 *
	 * @return The test6.
	 */
	public DtoTestObject2[] getTest6() {
		return this.test6;
	}

	/**
	 * Sets the test6.
	 *
	 * @param test6 New test6.
	 */
	public void setTest6(
			final DtoTestObject2[] test6) {
		this.test6 = test6;
	}

	/**
	 * Gets the test7.
	 *
	 * @return The test7.
	 */
	public int getTest7() {
		return this.test7;
	}

	/**
	 * Sets the test7.
	 *
	 * @param test7 New test7.
	 */
	public void setTest7(
			final int test7) {
		this.test7 = test7;
	}

	/**
	 * Gets the test8.
	 *
	 * @return The test8.
	 */
	@JsonGetter(value = "test88")
	@DtoAttribute(name = "test88")
	public int[] getTest8() {
		return this.test8;
	}

	/**
	 * Sets the test8.
	 *
	 * @param test8 New test8.
	 */
	public void setTest8(
			final int[] test8) {
		this.test8 = test8;
	}

	/**
	 * Gets the test9.
	 *
	 * @return The test9.
	 */
	@DtoAttribute(usedInComparison = false)
	public Integer getTest9() {
		return this.test9;
	}

	/**
	 * Sets the test9.
	 *
	 * @param test9 New test9.
	 */
	public void setTest9(
			final Integer test9) {
		this.test9 = test9;
	}

	/**
	 * Gets the test10.
	 *
	 * @return The test10.
	 */
	public List<Integer> getTest10() {
		return this.test10;
	}

	/**
	 * Sets the test10.
	 *
	 * @param test10 New test10.
	 */
	public void setTest10(
			final List<Integer> test10) {
		this.test10 = test10;
	}

	/**
	 * @see java.lang.Object#hashCode()
	 */
	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = (prime * result) + Arrays.hashCode(this.test6);
		result = (prime * result) + Arrays.hashCode(this.test8);
		result = (prime * result) + Objects.hash(this.id, this.test1, this.test10, this.test2, this.test3, this.test4, this.test5, this.test7, this.test9);
		return result;
	}

	/**
	 * @see java.lang.Object#equals(java.lang.Object)
	 */
	@Override
	public boolean equals(
			final Object obj) {
		if (this == obj) {
			return true;
		}
		if (!(obj instanceof DtoTestObject)) {
			return false;
		}
		final DtoTestObject other = (DtoTestObject) obj;
		return Objects.equals(this.id, other.id) && Objects.equals(this.test1, other.test1) && Objects.equals(this.test10, other.test10)
				&& Objects.equals(this.test2, other.test2) && Objects.equals(this.test3, other.test3) && Objects.equals(this.test4, other.test4)
				&& Objects.equals(this.test5, other.test5) && Arrays.equals(this.test6, other.test6) && (this.test7 == other.test7)
				&& Arrays.equals(this.test8, other.test8) && Objects.equals(this.test9, other.test9);
	}

}
