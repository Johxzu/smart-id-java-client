package ee.sk.smartid.util;

/*-
 * #%L
 * Smart ID sample Java client
 * %%
 * Copyright (C) 2018 - 2025 SK ID Solutions AS
 * %%
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 * #L%
 */

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ee.sk.smartid.AuthenticationIdentity;
import ee.sk.smartid.exception.UnprocessableSmartIdResponseException;

/**
 * Utility class for handling national identity numbers (personal codes).
 */
public class NationalIdentityNumberUtil {

    private static final Logger logger = LoggerFactory.getLogger(NationalIdentityNumberUtil.class);

    private static final DateTimeFormatter DATE_FORMATTER_YYYY_MM_DD = DateTimeFormatter.ofPattern("uuuuMMdd")
            .withResolverStyle(ResolverStyle.STRICT);

    private static final Pattern BE_PERSON_CODE_PATTERN = Pattern.compile("[0-9]{11}");
    private static final String BE_UNKNOWN_BIRTH_MONTH_AND_DAY = "0000";
    private static final int BE_CHECK_DIGITS_MODULUS = 97;

    /**
     * Detect date-of-birth from a national identification number if possible or return null.
     * <p>
     * This method always returns the value for all Estonian and Lithuanian national identification numbers.
     * <p>
     * It also works for older Latvian personal codes but Latvian personal codes issued after July 1st 2017
     * (starting with "32") do not carry date-of-birth.
     * <p>
     * Belgian national register numbers carry date-of-birth unless the birth month and day are unknown (both are zeroes).
     * <p>
     * For other countries (countries other than Estonia, Latvia, Lithuania or Belgium) it always returns null
     * (even if it would be possible to deduce date of birth from national identity number).
     * <p>
     * Newer (but not all) Smart-ID certificates have date-of-birth on a separate attribute.
     * It is recommended to use that value if present.
     *
     * @param authenticationIdentity Authentication identity
     * @return DateOfBirth or null if it cannot be detected from personal code
     * @see CertificateAttributeUtil#getDateOfBirth(java.security.cert.X509Certificate)
     */
    public static LocalDate getDateOfBirth(AuthenticationIdentity authenticationIdentity) {
        String identityNumber = authenticationIdentity.getIdentityNumber();

        return switch (authenticationIdentity.getCountry().toUpperCase()) {
            case "EE", "LT" -> parseEeLtDateOfBirth(identityNumber);
            case "LV" -> parseLvDateOfBirth(identityNumber);
            case "BE" -> parseBeDateOfBirth(identityNumber);
            default -> null;
        };
    }

    /**
     * Parses date of birth from Estonian or Lithuanian national identity number.
     *
     * @param eeOrLtNationalIdentityNumber Estonian or Lithuanian national identity number
     * @return Date of birth
     * @throws UnprocessableSmartIdResponseException if the national identity number is invalid or date cannot be parsed
     */
    public static LocalDate parseEeLtDateOfBirth(String eeOrLtNationalIdentityNumber) {
        String birthDate = eeOrLtNationalIdentityNumber.substring(1, 7);

        birthDate = switch (eeOrLtNationalIdentityNumber.substring(0, 1)) {
            case "1", "2" -> "18" + birthDate;
            case "3", "4" -> "19" + birthDate;
            case "5", "6" -> "20" + birthDate;
            default -> throw new RuntimeException("Invalid personal code " + eeOrLtNationalIdentityNumber);
        };

        try {
            return LocalDate.parse(birthDate, DATE_FORMATTER_YYYY_MM_DD);
        } catch (DateTimeParseException e) {
            throw new UnprocessableSmartIdResponseException("Could not parse birthdate from nationalIdentityNumber=" + eeOrLtNationalIdentityNumber, e);
        }
    }

    /**
     * Parses date of birth from Latvian national identity number if possible.
     * <p>
     * Latvian personal codes issued after July 1st 2017 (starting with "32") do not carry date-of-birth and null is returned.
     *
     * @param lvNationalIdentityNumber Latvian national identity number
     * @return Date of birth or null if the personal code does not carry birthdate info
     * @throws UnprocessableSmartIdResponseException if the national identity number is invalid or date cannot be parsed
     */
    public static LocalDate parseLvDateOfBirth(String lvNationalIdentityNumber) {
        String birthDay = lvNationalIdentityNumber.substring(0, 2);
        if (isNonParsableLVPersonCodePrefix(birthDay)) {
            logger.debug("Person has newer type of Latvian ID-code that does not carry birthdate info");
            return null;
        }

        String birthMonth = lvNationalIdentityNumber.substring(2, 4);
        String birthYearTwoDigit = lvNationalIdentityNumber.substring(4, 6);
        String century = lvNationalIdentityNumber.substring(7, 8);
        String birthDateYyyyMmDd = switch (century) {
            case "0" -> "18" + (birthYearTwoDigit + birthMonth + birthDay);
            case "1" -> "19" + (birthYearTwoDigit + birthMonth + birthDay);
            case "2" -> "20" + (birthYearTwoDigit + birthMonth + birthDay);
            default -> throw new UnprocessableSmartIdResponseException("Invalid personal code: " + lvNationalIdentityNumber);
        };

        try {
            return LocalDate.parse(birthDateYyyyMmDd, DATE_FORMATTER_YYYY_MM_DD);
        } catch (DateTimeParseException e) {
            throw new UnprocessableSmartIdResponseException("Unable get birthdate from Latvian personal code " + lvNationalIdentityNumber, e);
        }
    }

    /**
     * Parses date of birth from Belgian national register number if possible.
     * <p>
     * The number consists of 11 digits in the form YYMMDDSSSCC. As the birth year is given with two digits only,
     * the century is deduced from the check digits: they are calculated over the first 9 digits for persons born
     * before 2000 and over the same digits prefixed with "2" for persons born in 2000 or later.
     * <p>
     * If birth month and day are not known (both are zeroes) then null is returned.
     *
     * @param beNationalIdentityNumber Belgian national register number
     * @return Date of birth or null if the personal code does not carry birthdate info
     * @throws UnprocessableSmartIdResponseException if the national identity number is invalid or date cannot be parsed
     */
    public static LocalDate parseBeDateOfBirth(String beNationalIdentityNumber) {
        if (beNationalIdentityNumber == null || !BE_PERSON_CODE_PATTERN.matcher(beNationalIdentityNumber).matches()) {
            throw new UnprocessableSmartIdResponseException("Invalid personal code: " + beNationalIdentityNumber);
        }

        String birthMonthAndDay = beNationalIdentityNumber.substring(2, 6);
        if (BE_UNKNOWN_BIRTH_MONTH_AND_DAY.equals(birthMonthAndDay)) {
            logger.debug("Person has a Belgian national register number that does not carry birthdate info");
            return null;
        }

        String birthDateYyyyMmDd = determineBeBirthYear(beNationalIdentityNumber) + birthMonthAndDay;
        try {
            return LocalDate.parse(birthDateYyyyMmDd, DATE_FORMATTER_YYYY_MM_DD);
        } catch (DateTimeParseException e) {
            throw new UnprocessableSmartIdResponseException("Unable get birthdate from Belgian personal code " + beNationalIdentityNumber, e);
        }
    }

    private static String determineBeBirthYear(String beNationalIdentityNumber) {
        long codeWithoutCheckDigits = Long.parseLong(beNationalIdentityNumber.substring(0, 9));
        int checkDigits = Integer.parseInt(beNationalIdentityNumber.substring(9));
        String birthYearTwoDigit = beNationalIdentityNumber.substring(0, 2);

        boolean bornBefore2000 = BE_CHECK_DIGITS_MODULUS - (codeWithoutCheckDigits % BE_CHECK_DIGITS_MODULUS) == checkDigits;
        return (bornBefore2000 ? "19" : "20") + birthYearTwoDigit;
    }

    private static boolean isNonParsableLVPersonCodePrefix(String prefix) {
        Pattern pattern = Pattern.compile("3[2-9]");
        Matcher matcher = pattern.matcher(prefix);
        return matcher.matches();
    }
}
