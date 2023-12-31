/*
 * Copyright 2012-2021 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * 	http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */
package org.nexial.core.plugins.mobile

import io.appium.java_client.AppiumDriver
import io.appium.java_client.MobileBy
import io.appium.java_client.MobileElement
import org.apache.commons.collections4.CollectionUtils
import org.apache.commons.lang3.StringUtils
import org.apache.commons.lang3.math.NumberUtils
import org.nexial.commons.utils.RegexUtils
import org.nexial.commons.utils.TextUtils
import org.nexial.commons.utils.TextUtils.*
import org.nexial.core.NexialConst.RB
import org.nexial.core.plugins.mobile.MobileType.*
import org.nexial.core.plugins.web.LocatorHelper.Companion.normalizeXpathText
import org.nexial.core.utils.CheckUtils.requiresInteger
import org.nexial.core.utils.CheckUtils.requiresPositiveNumber
import org.nexial.core.utils.ConsoleUtils
import org.openqa.selenium.By
import java.lang.Integer.MAX_VALUE

/**
 * References:
 * https://developer.android.com/intl/ru/training/accessibility/accessible-app.html
 * https://developer.apple.com/library/ios/documentation/UIKit/Reference/UIAccessibilityIdentification_Protocol/index.html
 */
class MobileLocatorHelper(private val mobileService: MobileService) {
    private val prefixId = "id"
    private val prefixAccessibility = "a11y"
    private val prefixClass = "class"
    private val prefixXPath = "xpath"
    private val prefixResourceId = "res"
    private val prefixPredicate = "predicate"
    private val prefixClassChain = "cc"
    private val prefixName = "name"
    private val prefixText = "text"
    private val prefixNearby = "nearby"
    private val prefixDescription = "desc"

    // todo
    private val prefixImage = "image"

    private val xpathStartsWith = listOf("/", "./", "(/", "( /", "(./", "( ./")

    internal fun resolve(locator: String, allowRelative: Boolean): By {
        if (StringUtils.isBlank(locator)) throw IllegalArgumentException(RB.Mobile.text("invalid.locator", locator))

        // default
        for (startsWith in xpathStartsWith)
            if (StringUtils.startsWith(locator, startsWith))
                return By.xpath(if (allowRelative) locator else fixBadXpath(locator))
        if (StringUtils.containsNone(locator, "=")) return By.id(locator)

        val strategy = StringUtils.trim(StringUtils.lowerCase(StringUtils.substringBefore(locator, "=")))
        val loc = StringUtils.substringAfter(locator, "=")
        val locTrimmed = StringUtils.trim(loc)
        val normalized = normalizeXpathText(locTrimmed)
        val isIOS = mobileService.isIOS()
        val isAndroid = mobileService.isAndroid()

        return when (strategy) {
            // standard ones
            prefixId, prefixName -> By.id(locTrimmed)
            prefixResourceId     -> By.xpath("//*[@resource-id=$normalized]")
            prefixAccessibility  -> MobileBy.AccessibilityId(locTrimmed)
            prefixClass          -> By.className(locTrimmed)
            prefixXPath          -> By.xpath(if (allowRelative) locTrimmed else fixBadXpath(locTrimmed))
            prefixText           -> resolveTextLocator(loc)
            prefixNearby         -> handleNearbyLocator(mobileService.profile.mobileType, locTrimmed)

            // ios specific
            prefixPredicate      ->
                if (isIOS) MobileBy.iOSNsPredicateString(loc)
                else throw IllegalArgumentException(RB.Mobile.text("invalid.locator.ios", locator))
            prefixClassChain     ->
                if (isIOS) MobileBy.iOSClassChain(loc)
                else throw IllegalArgumentException(RB.Mobile.text("invalid.locator.ios", locator))

            // android specific
            prefixDescription    ->
                if (isAndroid) By.xpath("//*[@content-desc=$normalized]")
                else throw IllegalArgumentException(RB.Mobile.text("invalid.locator.android", locator))

            // catch all
            else                 -> return By.id(locTrimmed)
        }
    }

    // internal fun resolveAlt(locator: String): List<By> {
    //     if (StringUtils.isBlank(locator)) throw IllegalArgumentException("$INVALID_LOCATOR $locator")
    //
    //     val strategy = StringUtils.trim(StringUtils.lowerCase(StringUtils.substringBefore(locator, "=")))
    //     val loc = StringUtils.trim(StringUtils.substringAfter(locator, "="))
    //     val normalized = normalizeXpathText(loc)
    //
    //     val alt = mutableListOf<By>()
    //     when (strategy) {
    //         prefixResourceId  -> alt.add(By.xpath("//*[@resource-id=${normalized}]"))
    //         prefixDescription -> alt.add(By.xpath("//*[@name=${normalized}]"))
    //     }
    //     return alt
    // }

    private fun resolveTextLocator(text: String) =
        By.xpath("//*[${resolveTextFilter(mobileService.profile.mobileType, text)}]")

    internal fun resolve(locator: String) = resolve(locator, false)

    internal fun fixBadXpath(locator: String?): String? {
        val loc = StringUtils.trim(locator)
        return when {
            StringUtils.isEmpty(loc)             -> locator
            StringUtils.startsWith(loc, ".//")   -> StringUtils.substring(loc, 1)
            StringUtils.startsWith(loc, "(.//")  -> "(" + StringUtils.substring(loc, 2)
            StringUtils.startsWith(loc, "( .//") -> "(" + StringUtils.substring(loc, 3)
            else                                 -> loc
        }
    }

    internal fun resolveCompoundLocators(mobileType: MobileType, compoundLocator: String): List<By> {
        if (!RegexUtils.isExact(StringUtils.trim(compoundLocator), regexOneOfLocator))
            throw IllegalArgumentException(RB.Mobile.text("invalid.locator.oneOf", compoundLocator))

        val regexPlatform = "^${mobileType.platformName.lowercase()};.+"

        // filter out those that are platform-specific but not fitting to target platform
        val locators =
            TextUtils.groups(compoundLocator.substringAfter(prefixOneOfLocator), "{", "}", false).filter { locator ->
                StringUtils.isNotBlank(locator) &&
                (!RegexUtils.isExact(StringUtils.trim(locator), regexOneOfPlatformSpecific) ||
                 RegexUtils.isExact(StringUtils.trim(locator), regexPlatform))
            }

        if (CollectionUtils.isEmpty(locators))
            throw IllegalArgumentException(RB.Mobile.text("misfit.locator.oneOf", compoundLocator))

        return locators.map { locator -> resolve(locator.trim()) }
    }

    companion object {
        private const val leftOf = "left-of"
        private const val rightOf = "right-of"
        private const val above = "above"
        private const val below = "below"
        private const val item = "item"
        private const val container = "container"
        private const val scrollContainer = "scroll-container"

        private const val prefixOneOfLocator = "one-of="
        internal const val regexOneOfLocator = "${prefixOneOfLocator}(\\s*\\{.+\\}\\s*)+"
        private const val regexOneOfPlatformSpecific = "^(android|ios);\\s*.+"
        private const val regexNearbyNameValueSpec = "\\s*.+\\s*[=:]\\s*.+\\s*"
        private const val regexSurrounding =
            "^\\s*($leftOf|$rightOf|$above|$below|$container|$scrollContainer|$item)\\s*:\\s*.+$"

        const val prefixIndex = "index:"
        const val scrollableLocator = "//*[@scrollable='true' and @displayed='true' and @enabled='true']"
        const val scrollableLocator2 = "//android.widget.ScrollView[@displayed='true' and @enabled='true']"
        const val iosAlertLocator = "//*[@type='XCUIElementTypeAlert' and @visible='true']"
        const val scriptPressButton = "mobile: pressButton"
        const val scriptScript = "mobile: swipe"
        const val scriptSelectPickerValue = "mobile: selectPickerWheelValue"
        const val doneLocator = "name=Done"
        const val pickerWheelLocator = "//XCUIElementTypePickerWheel"
        const val iosKeyboardDoneLocator = "//XCUIElementTypeKeyboard//XCUIElementTypeButton[@name='Done']"

        internal const val clearAllNotificationsLocators = prefixOneOfLocator +
                                                           "{text=Clear all}" +
                                                           "{android;id=com.android.systemui:id/dismiss_text}" +
                                                           "{android;id=com.android.systemui:id/clear_all_button}"

        /**
         * Expected format: `nearby={left-of|right-of|below|above:text}{attribute_with_value_as_true,attribute=value,...}`
         *
         * Only looking for element that are visible and usable, hence implied:
         * - android: @displayed='true' and @enabled='true'
         * - ios: @visible='true' and @enabled='true'
         *
         * Example:
         *  `nearby={left-of=None of these}{clickable,enabled}`
         *  `nearby={right-of=Yes}{clickable,class=android.widget.GroupView}`
         */
        internal fun handleNearbyLocator(mobileType: MobileType, specs: String): By {
            if (StringUtils.isBlank(specs)) throw IllegalArgumentException(RB.Mobile.text("bad.nearby", specs))
            if (!TextUtils.isBetween(specs.trim(), "{", "}"))
                throw IllegalArgumentException(RB.Mobile.text("invalid.nearby", specs))

            val parts = mutableListOf("@enabled='true'")
            when {
                mobileType.isAndroid() -> parts.add("@displayed='true'")
                mobileType.isIOS()     -> parts.add("@visible='true'")
            }

            // aggressively trim off extraneous leading/trailing spaces
            val ancestorBuilder = StringBuilder()
            var index = -1
            TextUtils.groups(specs.trim(), "{", "}", false).forEach { group ->
                group.split(",").forEach { part ->
                    if (part.matches(Regex(regexNearbyNameValueSpec))) {
                        val useNearByHint = RegexUtils.match(part, regexSurrounding)
                        val name = part.substringBefore(if (useNearByHint) ":" else "=").trim()
                        val value = part.substringAfter(if (useNearByHint) ":" else "=").trim()
                        val textFilter = resolveLinkTextFilter(mobileType, value)
                        val siblingTextFilter = "[$textFilter or .//*[$textFilter]]"
                        when (name) {
                            leftOf, above   -> {
                                parts.add("following-sibling::*[1]$siblingTextFilter")
                                index = MAX_VALUE
                            }

                            rightOf, below  -> {
                                parts.add("preceding-sibling::*[1]$siblingTextFilter")
                                index = 1
                            }

                            container       -> {
                                parts.add(textFilter)
                                ancestorBuilder.append("/ancestor::*[contains(${lower("class", "group")},'group')]")
                            }

                            scrollContainer -> {
                                parts.add(textFilter)
                                ancestorBuilder.append("/ancestor::*[contains(${lower("class", "scroll")},'scroll')]")
                            }

                            item            -> {
                                index = if (value == "last")
                                    MAX_VALUE
                                else {
                                    requiresInteger(value, "item value must be a number", part)
                                    val itemIndex = NumberUtils.toInt(value)
                                    if (itemIndex < 1)
                                        throw IllegalArgumentException("item value must be greater than 0")
                                    itemIndex
                                }
                            }

                            else            -> parts.add(resolveFilter(name, value))
                        }
                    } else
                        parts.add("@${part.trim()}='true'")
                }
            }

            // consider index (ie. {item:...})
            var xpath = parts.joinToString(prefix = "//*[", separator = " and ", postfix = "]")
            if (ancestorBuilder.isNotBlank())
                xpath = "($xpath$ancestorBuilder)[last()${if (index > 0) "-$index" else ""}]"
            else if (index > 0) xpath = "($xpath)[${if (index == MAX_VALUE) "last()" else "" + index}]"

            ConsoleUtils.log("resolved $specs to $xpath")
            return By.xpath(xpath)
        }

        internal fun resolveAndroidDropdownItemLocator(item: String) =
            if (StringUtils.startsWith(item, prefixIndex))
                "(.//*[@text != ''])[${toIndexValue(item)}]"
            else
                ".//*[${resolveTextFilter(ANDROID, item)}]"

        internal fun selectIOSDropdown(driver: AppiumDriver<MobileElement>, picker: MobileElement, item: String) {
            val currentPickedValue = picker.getAttribute("value")
            if (StringUtils.equals(currentPickedValue, item)) return

            if (!StringUtils.startsWith(item, prefixIndex)) {
                picker.sendKeys(item)
                return
            }

            // since item is specified as `index:...`, let's make sure we've got a good index
            val index = toIndexValue(item)

            val pickerId = picker.id

            // if picker already has selection, we'll swipe to top of the dropdown first
            if (StringUtils.isNotBlank(currentPickedValue))
                driver.executeScript(
                    scriptScript,
                    mapOf<String, Any>("element" to pickerId, "direction" to "down", "velocity" to 250))

            val scrollAmount = 25.0 / picker.size.height

            for (i in 0 until index)
                driver.executeScript(
                    scriptSelectPickerValue,
                    mapOf<String, Any>("element" to pickerId, "order" to "next", "offset" to scrollAmount))
        }

        internal fun resolveTextFilter(mobileType: MobileType, text: String) = when (mobileType) {
            ANDROID, ANDROID_BROWSERSTACK -> resolveFilter("text", text)
            IOS, IOS_BROWSERSTACK         ->
                resolveFilter("label", text) +
                " or (contains(${lower("type", "text")},'text') and ${resolveFilter("value", text)})"
        }

        internal fun resolveLinkTextFilter(mobileType: MobileType, text: String) = when {
            mobileType.isAndroid() -> resolveFilter("text", text)
            mobileType.isIOS()     -> resolveFilter("label", text)
            else                   -> throw IllegalArgumentException("Mobile type '${mobileType}' is not supported")
        }

        internal fun resolveFilter(attribute: String, value: String) = when {
            StringUtils.startsWith(value, REGEX)            ->
                throw IllegalArgumentException(RB.PolyMatcher.text("notSupported.REGEX", "$attribute=$value"))

            StringUtils.startsWith(value, NUMERIC)          ->
                throw IllegalArgumentException(RB.PolyMatcher.text("notSupported.NUMERIC", "$attribute=$value"))

            StringUtils.startsWith(value, CONTAIN)          ->
                "contains(@$attribute,${normalizeText(value, after = CONTAIN)})"

            StringUtils.startsWith(value, CONTAIN_ANY_CASE) -> {
                // Android and iOS compatible approach
                val valueLower = value.substringAfter(CONTAIN_ANY_CASE).lowercase()
                "contains(${lower(attribute, valueLower)},'$valueLower')"
            }

            StringUtils.startsWith(value, START)            ->
                "starts-with(@$attribute,${normalizeText(value, after = START)})"

            StringUtils.startsWith(value, START_ANY_CASE)   -> {
                // Android and iOS compatible approach
                val valueLower = value.substringAfter(START_ANY_CASE).lowercase()
                "starts-with(${lower(attribute, valueLower)},'$valueLower')"
            }

            StringUtils.startsWith(value, END)              ->
                "ends-with(@$attribute,${normalizeText(value, after = END)})"

            StringUtils.startsWith(value, END_ANY_CASE)     -> {
                // Android and iOS compatible approach
                val valueLower = value.substringAfter(END_ANY_CASE).lowercase()
                "ends-with(${lower(attribute, valueLower)},'$valueLower')"
            }

            StringUtils.startsWith(value, LENGTH)           ->
                "string-length(@$attribute)=${normalizeText(value, after = LENGTH)}"

            StringUtils.startsWith(value, EXACT)            -> "@$attribute=${normalizeText(value, after = EXACT)}"

            else                                            ->
                "@$attribute=${normalizeXpathText(value)}"
        }

        private fun toIndexValue(item: String): Int {
            val indexString = StringUtils.trim(StringUtils.substringAfter(item, prefixIndex))
            if (!NumberUtils.isDigits(indexString))
                throw IllegalArgumentException(RB.Mobile.text("invalid.index", indexString))

            val index = NumberUtils.toInt(indexString)
            if (index < 0) throw IllegalArgumentException(RB.Mobile.text("invalid.index.value", indexString))

            // 0-based
            return index + 1
        }

        private fun normalizeText(text: String, after: String): String {
            var matchBy = text.substringAfter(after)

            if (after == LENGTH) {
                matchBy = matchBy.trim()
                requiresPositiveNumber(matchBy, RB.Mobile.text("invalid.length"), matchBy)
            }

            return normalizeXpathText(matchBy)
        }

        private fun lower(attribute: String, text: String) =
            "translate(@$attribute,\"${text.uppercase()}\",\"${text.lowercase()}\")"
    }
}
