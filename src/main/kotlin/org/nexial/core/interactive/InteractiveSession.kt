/*
 * Copyright 2012-2018 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.nexial.core.interactive

import org.apache.commons.collections4.BidiMap
import org.apache.commons.collections4.MapUtils
import org.apache.commons.collections4.bidimap.DualLinkedHashBidiMap
import org.apache.commons.lang3.StringUtils
import org.apache.commons.lang3.SystemUtils.USER_NAME
import org.apache.commons.lang3.math.NumberUtils
import org.nexial.commons.utils.EnvUtils
import org.nexial.commons.utils.FileUtil
import org.nexial.core.ExecutionInputPrep
import org.nexial.core.ExecutionThread
import org.nexial.core.NexialConst.Data.DEF_OPEN_EXCEL_AS_DUP
import org.nexial.core.NexialConst.Iteration.*
import org.nexial.core.excel.Excel
import org.nexial.core.excel.ExcelAddress
import org.nexial.core.excel.ExcelArea
import org.nexial.core.excel.ExcelConfig.*
import org.nexial.core.model.ExecutionContext
import org.nexial.core.model.ExecutionDefinition
import org.nexial.core.model.StepResult
import org.nexial.core.model.TestProject
import org.nexial.core.model.TestScenario
import org.nexial.core.plugins.base.BaseCommand
import org.nexial.core.utils.ConsoleUtils
import org.nexial.core.utils.ExecUtils.IGNORED_CLI_OPT
import org.nexial.core.utils.ExecUtils.deriveJavaOpts
import org.nexial.core.utils.InputFileUtils
import java.io.File
import java.io.File.separator
import java.util.*
import kotlin.math.min

data class InteractiveSession(val context: ExecutionContext) {

    // system
    val startTime: Long = System.currentTimeMillis()
    val hostname: String = EnvUtils.getHostName()
    val user: String = USER_NAME
    var excel: Excel? = null

    // helpers
    private val baseCommand = context.findPlugin("base") as BaseCommand
    val executionInspector = ExecutionInspector(baseCommand)
    val executionRecorder = ExecutionRecorder(baseCommand)

    var executionDefinition: ExecutionDefinition? = null
        @Throws(IllegalArgumentException::class)
        set(value) {
            if (value == null) throw IllegalArgumentException("ExecutionDefinition MUST not be null")

            field = value

            // save scenario, in case we can use it
            val execScenario = value.scenarios[0] ?: ""
            val dataSheet = value.dataSheets[0] ?: ""

            // setter will override scenario & data sheet information
            script = value.testScript

            if (dataSheet != "") {
                value.dataSheets.clear()
                value.dataSheets.add(dataSheet)
            }

            if (execScenario != "") {
                value.scenarios.clear()
                value.scenarios.add(execScenario)
            }

            if (value.dataFile != null) dataFile = value.dataFile.absolutePath

            if (execScenario != "") scenario = execScenario
        }

    // inflight / flyweight pattern
    var inflightScript: Excel? = null
    var inflightScenario: TestScenario? = null

    // user input
    var script: String? = null
        set(value) {
            if (FileUtil.isFileReadable(value)) {
                val reloadExcel = !StringUtils.equals(field, value)
                field = value
                loadTestScript(reloadExcel)
            } else {
                // maybe it's another script from the same project
                val newScriptPath =
                    if (executionDefinition != null && executionDefinition!!.project.scriptPath != null) {
                        val script = StringUtils.appendIfMissing(executionDefinition!!.project.scriptPath, separator) +
                                     value
                        if (FileUtil.isFileReadable(script)) script else null
                    } else null
                if (newScriptPath != null) {
                    val reloadExcel = !StringUtils.equals(field, newScriptPath)
                    field = newScriptPath
                    loadTestScript(reloadExcel)
                } else {
                    ConsoleUtils.error("Invalid script specified: $value")
                    excel = null
                    allScenarios.clear()
                    allActivities.clear()
                    allSteps.clear()
                }
            }
        }

    var autoRun = false

    init {
        System.getProperties().toMap().forEach { (name, _) ->
            val n = name.toString()
            if (IGNORED_CLI_OPT.none { StringUtils.startsWith(n, it) }) context.setData(n, System.getProperty(n))
        }

        ExecutionThread.set(context)
    }

    private fun loadTestScript(reloadExcel: Boolean) {
        if (reloadExcel) {
            excel = Excel(File(script!!), DEF_OPEN_EXCEL_AS_DUP, false)
            inflightScript = null
        }

        if (executionDefinition != null) executionDefinition!!.testScript = script

        // check if current scenario is valid / if not use first valid one
        allScenarios.clear()
        allScenarios.addAll(InputFileUtils.retrieveValidTestScenarioNames(excel))
        if (StringUtils.isBlank(scenario) || !allScenarios.contains(scenario)) {
            scenario = if (allScenarios.isEmpty()) "" else allScenarios[0]
        } else {
            collectScenarioDetails()
            calibrateActivitiesAndSteps()
        }
    }

    fun reloadTestScript() {
        when {
            script == null                     -> ConsoleUtils.error("No test script assigned.")
            !FileUtil.isFileReadable(dataFile) -> ConsoleUtils.error("Assigned test script is not readable: $script")
            else                               -> loadTestScript(true)
        }
    }

    fun reloadProjectProperties() {
        val projectHome = executionDefinition?.project?.projectHome
        if (projectHome != null) {
            executionDefinition?.project?.projectHome = projectHome
            TestProject.listProjectPropertyKeys().forEach { key ->
                System.setProperty(key, TestProject.getProjectProperty(key))
            }
        }
    }

    var dataFile: String? = null
        set(value) {
            if (FileUtil.isFileReadable(value)) {
                field = value
                loadDataFile()
            } else {
                // maybe it's another data file from the same project
                val newDataFile = if (executionDefinition != null && executionDefinition!!.project.dataPath != null) {
                    val script = StringUtils.appendIfMissing(executionDefinition!!.project.dataPath, separator) + value
                    if (FileUtil.isFileReadable(script)) script else null
                } else null
                if (newDataFile != null) {
                    field = value
                    loadDataFile()
                } else {
                    ConsoleUtils.error("Invalid data file specified: $value")
                }
            }
        }

    private fun loadDataFile() {
        if (executionDefinition == null) return

        val execDef = executionDefinition!!

        val dataFileObj = File(dataFile!!)
        val dataFileOfValue = dataFileObj.absolutePath
        if (execDef.dataFile == null || !StringUtils.equals(execDef.dataFile.absolutePath, dataFileOfValue)) {
            execDef.dataFile = dataFileObj
        }

        execDef.getTestData(true)
        if (this.iteration == 0) {
            val iterationValue = StringUtils.trim(execDef.testData.iteration)
            this.iteration = if (NumberUtils.isDigits(iterationValue)) {
                NumberUtils.toInt(iterationValue)
            } else {
                val firstIteration = iterationValue.substringBefore(ITERATION_SEP)
                    .substringBefore(ITERATION_RANGE_SEP)
                    .trim()
                ExcelAddress.fromColumnLettersToOrdinalNumber(firstIteration) - 1
            }
        }
    }

    fun reloadDataFile() {
        when {
            dataFile == null                   -> ConsoleUtils.error("No data file assigned.")
            !FileUtil.isFileReadable(dataFile) -> ConsoleUtils.error("Assigned data file is not readable: $dataFile")
            else                               -> loadDataFile()
        }
    }

    private val allScenarios: MutableList<String> = mutableListOf()
    var scenario: String? = null
        set(value) {
            if (excel != null && allScenarios.isNotEmpty()) {
                if (!allScenarios.contains(value)) {
                    ConsoleUtils.error("Invalid scenario specified: $value")
                } else {
                    field = value
                    executionDefinition!!.scenarios = mutableListOf(scenario)
                    executionDefinition!!.dataSheets = mutableListOf(scenario)
                    collectScenarioDetails()
                    calibrateActivitiesAndSteps()
                }
            }
        }

    private fun collectScenarioDetails() {
        allActivities.clear()
        allSteps.clear()
        activityStepMap.clear()
        clearActivities()
        clearSteps()

        context.clearScreenshotAgent()

        // collect activities and steps for the specified scenario
        val worksheet = excel!!.worksheet(scenario)
        val lastCommandRow = worksheet.findLastDataRow(ADDR_COMMAND_START)
        val stepArea = ExcelAddress("$FIRST_STEP_ROW:$COL_REASON$lastCommandRow")
        val area = ExcelArea(worksheet, stepArea, false)

        val scenarioRef = "Error found in [${worksheet.file.name}][${worksheet.name}]"

        var currentActivity = ""
        var i = 0
        while (i < area.wholeArea.size) {
            val row = area.wholeArea[i]
            val errorPrefix = "$scenarioRef[${row[COL_IDX_TESTCASE].reference}]: "
            val activityName = Excel.getCellValue(row[COL_IDX_TESTCASE])
            if (i == 0 && StringUtils.isBlank(activityName)) {
                throw RuntimeException("$errorPrefix Invalid format; First row must contain valid activity name")
            }

            if (StringUtils.isNotEmpty(activityName) && StringUtils.isAllBlank(activityName)) {
                throw RuntimeException("$errorPrefix Found invalid, space-only activity name")
            }

            val currentRow = "" + (row[COL_IDX_COMMAND].rowIndex + 1)

            if (StringUtils.isNotBlank(activityName)) {
                if (allActivities.containsValue(activityName)) {
                    throw RuntimeException("$errorPrefix Found duplicate activity name '$activityName'")
                }

                allActivities[allActivities.size + 1] = activityName

                if (StringUtils.isEmpty(currentActivity)) {
                    // first time... probably first row in this cell area
                    currentActivity = activityName
                    activityStepMap[currentActivity] = mutableListOf()
                } else {
                    if (!StringUtils.equals(currentActivity, activityName)) {
                        currentActivity = activityName
                        activityStepMap[currentActivity] = mutableListOf()
                    }
                }
            }

            activityStepMap[currentActivity]!!.add(currentRow)
            allSteps.add(currentRow)
            i++
        }
    }

    var iteration: Int = 0
        set(value) {
            field = value

            if (executionDefinition != null && executionDefinition!!.testData != null) {
                System.setProperty(ITERATION, field.toString())
                executionDefinition!!.parse()
                val testData = executionDefinition!!.testData

                // handle iteration config/data
                testData.allSettings[ITERATION] = field.toString()
                val iterationManager = testData.iterationManager
                val iterationRef = iterationManager.getIterationRef(field - 1)

                val data = TreeMap(testData.getAllValue(field))
                data[ITERATION] = field.toString()
                testData.allSettings.forEach { (key, value) -> data[key] = value }
                data.putAll(deriveJavaOpts())

                data[CURR_ITERATION] = "1"
                if (iterationRef != -1) data[CURR_ITERATION_ID] = iterationRef.toString() + ""
                if (field > 1) {
                    val lastIterationRef = iterationManager.getIterationRef(field - 2)
                    if (lastIterationRef != -1) data[LAST_ITERATION] = lastIterationRef.toString()
                    data[IS_FIRST_ITERATION] = "false"
                } else {
                    data[IS_FIRST_ITERATION] = "true"
                }
                data[IS_LAST_ITERATION] = if (field == iterationManager.iterationCount) "true" else "false"

                val sysProps = System.getProperties()
                if (MapUtils.isNotEmpty(sysProps))
                    sysProps.forEach { propName, propValue -> data[propName.toString()] = propValue.toString() }

                // remove all excluded data variables
                val dataNames = data.keys.toTypedArray()
                Arrays.stream(dataNames).forEach {
                    if (StringUtils.isBlank(it) || ExecutionInputPrep.shouldExcludeDataVariable(it)) data.remove(it)
                }

                data.forEach { (key, dataValue) -> context.setData(key, dataValue) }
            }
        }

    private val allActivities: BidiMap<Int, String> = DualLinkedHashBidiMap()
    var activities: MutableList<String> = mutableListOf()
        set(value) {
            if (excel != null && allActivities.isNotEmpty() && value.isNotEmpty()) {
                field.clear()
                field.addAll(value)
                clearSteps()
            }
        }

    fun assignActivities(inputs: List<String>) {
        if (excel == null) {
            ConsoleUtils.error("Currently no test script assigned!")
            return
        }

        if (allScenarios.isEmpty()) {
            ConsoleUtils.error("No scenarios found in currently assigned test script!")
            return
        }

        if (scenario == null) {
            ConsoleUtils.error("Currently no scenario assigned!")
            return
        }

        if (allActivities.isEmpty) {
            ConsoleUtils.error("No activities found in currently assigned scenario!")
            return
        }

        if (inputs.isEmpty()) {
            ConsoleUtils.error("No activities specified!")
            return
        }

        if (inputs.size == 1 && inputs[0] == "*") {
            // reset to all activities
            useAllActivities()
            return
        }

        val mappedActivities = mutableListOf<String>()
        inputs.forEach { input ->
            run {
                var found = false
                allActivities.forEach { entry ->
                    // user specified by activity index or activity name
                    if (entry.key.toString() == input || entry.value == input) {
                        mappedActivities.add(entry.value)
                        found = true
                    }
                }

                if (!found) {
                    ConsoleUtils.error("Invalid activity index or name specified: $input")
                    return
                }
            }
        }

        // all activities are valid
        activities = mappedActivities
    }

    fun clearActivities() = activities.clear()

    fun useAllActivities() {
        clearSteps()
        clearActivities()
        activities.addAll(allActivities.values)
    }

    fun formatActivities(activities: MutableList<String>, preferredLength: Int, maxLength: Int): List<String> {
        val activityDisplay = activities.map { activity -> "${allActivities.getKey(activity)}:$activity" }.toList()
        val longestActivityLength =
            (
                if (activityDisplay.isEmpty()) preferredLength
                else min(activityDisplay.maxOf { display -> display.length }, preferredLength)
            ) + 2

        val headers2 = mutableListOf<String>()
        var currentLine = ""
        activityDisplay.forEach { display ->
            val display2 = StringUtils.rightPad(display, longestActivityLength, " ")
            when {
                display2.length > maxLength                        -> {
                    if (currentLine.isNotEmpty()) headers2.add(currentLine)
                    headers2.add(display2)
                    currentLine = ""
                }
                display2.length > longestActivityLength           -> {
                    if (currentLine.isNotEmpty()) headers2.add(currentLine)
                    headers2.add(display2)
                    currentLine = ""
                }
                (currentLine.length + display2.length) > maxLength -> {
                    if (currentLine.isNotEmpty()) headers2.add(currentLine)
                    currentLine = display2
                }
                else                                               -> {
                    currentLine += display2
                }
            }
        }

        if (currentLine.isNotEmpty()) headers2.add(currentLine)
        return headers2
    }

    private val activityStepMap: MutableMap<String, MutableList<String>> = mutableMapOf()

    private val allSteps: MutableList<String> = mutableListOf()
    var steps: MutableList<String> = mutableListOf()
        set(value) {
            if (excel != null && allSteps.isNotEmpty()) {
                if (value.isNotEmpty()) {
                    if (value.size == 1 && value[0] == "*") {
                        // reset to all steps
                        field.clear()
                        field.addAll(allSteps)
                        clearActivities()
                    } else if (value.find { step -> !allSteps.contains(step) } != null) {
                        // found invalid step
                        ConsoleUtils.error("Invalid step specified: $value")
                    } else {
                        // all steps are valid
                        field.clear()
                        field.addAll(value)
                        clearActivities()
                    }
                }
            }
        }

    private fun clearSteps() = steps.clear()

    // execution output
    val results: List<StepResult> = mutableListOf()

    var exception: Throwable? = null

    private fun calibrateActivitiesAndSteps() {
        if (activities.isEmpty() || activities.find { activity -> !allActivities.containsValue(activity) } != null) {
            // currently, without activity or one or more activities are invalid against `allActivities`
            clearActivities()

            // check if current steps are valid / if not use the ones from scenario
            if (steps.isEmpty() || steps.find { step -> !allSteps.contains(step) } != null) useAllActivities()
        } else {
            clearSteps()
        }
    }
}
