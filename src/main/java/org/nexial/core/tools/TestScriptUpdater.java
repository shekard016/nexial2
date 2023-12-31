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

package org.nexial.core.tools;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.Options;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.IterableUtils;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.apache.poi.ss.util.CellAddress;
import org.apache.poi.xssf.usermodel.XSSFCell;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.nexial.commons.utils.RegexUtils;
import org.nexial.commons.utils.ResourceUtils;
import org.nexial.commons.utils.TextUtils;
import org.nexial.core.excel.Excel;
import org.nexial.core.excel.Excel.Worksheet;
import org.nexial.core.excel.ExcelAddress;
import org.nexial.core.excel.ExcelArea;
import org.nexial.core.model.TestStep;
import org.nexial.core.tools.ScriptMetadata.Commands;
import org.nexial.core.tools.ScriptMetadata.NamedRange;
import org.nexial.core.utils.InputFileUtils;
import org.slf4j.MDC;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

import static java.io.File.separator;
import static org.nexial.core.CommandConst.*;
import static org.nexial.core.NexialConst.Data.SHEET_SYSTEM;
import static org.nexial.core.NexialConst.ExitStatus.RC_BAD_CLI_ARGS;
import static org.nexial.core.NexialConst.Project.BATCH_EXT;
import static org.nexial.core.NexialConst.Project.COMMAND_JSON_FILE_NAME;
import static org.nexial.core.NexialConst.RB;
import static org.nexial.core.excel.ExcelConfig.*;
import static org.nexial.core.tools.CliConst.OPT_VERBOSE;
import static org.nexial.core.tools.CliUtils.*;
import static org.nexial.core.tools.CliUtils.newArgOption;
import static org.nexial.core.tools.CliUtils.newNonArgOption;
import static org.nexial.core.tools.CommandDiscovery.GSON;

/**
 * utility to update one or more test scripts with the latest command listing.  The command listing is synced from
 * ${NEXIAL_HOME}/template.
 *
 * @see CommandMetaGenerator
 */
public class TestScriptUpdater {
    private static final Options cmdOptions = new Options();

    private boolean verbose;
    private List<File> targetFiles;
    private boolean fixDuplicateActivity;

    public static void main(String[] args) throws Exception {
        initOptions();

        TestScriptUpdater updater = newInstance(args);
        if (updater == null) { System.exit(RC_BAD_CLI_ARGS); }

        updater.update(updater.retrieveMetadata());
    }

    public void setTargetFiles(List<File> targetFiles) { this.targetFiles = targetFiles; }

    protected static TestScriptUpdater newInstance(String[] args) {
        TestScriptUpdater updater = new TestScriptUpdater();
        updater.parseCLIOptions(getCommandLine("nexial-script-update." + BATCH_EXT, args, cmdOptions));
        return updater;
    }

    protected void parseCLIOptions(CommandLine cmd) {
        if (!cmd.hasOption("t")) { throw new RuntimeException(RB.Tools.text("cli.missingRequired", "target")); }

        verbose = cmd.hasOption("v");
        fixDuplicateActivity = cmd.hasOption("u");

        String target = cmd.getOptionValue("t");
        File targetFile = new File(target);
        if (!targetFile.exists() || !targetFile.canRead()) {
            throw new RuntimeException(RB.Tools.text("target.bad", target));
        }

        targetFiles = new ArrayList<>();
        if (targetFile.isFile()) {
            if (verbose) { System.out.println(RB.Tools.text("target.isExcel", targetFile)); }
            targetFiles.add(targetFile);
        } else {
            targetFiles.addAll(
                FileUtils.listFiles(targetFile, new String[]{"xlsx"}, true).stream()
                         .filter(file -> !file.getName().startsWith("~") &&
                                         !file.getAbsolutePath().contains(separator + "output" + separator))
                         .collect(Collectors.toList()));
            if (verbose) { System.out.println(RB.Tools.text("target.isExcels", targetFiles.size())); }
        }
    }

    protected ScriptMetadata retrieveMetadata() {
        try {
            String commandJson = ResourceUtils.loadResource("/" + COMMAND_JSON_FILE_NAME);
            if (StringUtils.isEmpty(commandJson)) {
                System.err.println(RB.Fatal.text("cmdMeta.scriptUpdateErr"));
                System.exit(-1);
            }
            return GSON.fromJson(commandJson, ScriptMetadata.class);
        } catch (Exception e) {
            System.err.println(e.getMessage());
            System.err.println(RB.Fatal.text("cmdMeta.scriptUpdateErr"));
            System.exit(-1);
        }
        return null;
    }

    protected void update(final ScriptMetadata metadata) {
        targetFiles.forEach(file -> {
            try {
                String filePath = file.getAbsolutePath();
                String fileName = file.getName();
                Excel excel = new Excel(file);
                XSSFWorkbook workbook = excel.getWorkbook();

                if (InputFileUtils.isValidScript(excel)) {
                    System.out.println("processing " + filePath);

                    if (updateTemplate(excel) && verbose) { System.out.println(RB.Tools.text("updateTemplate")); }

                    handleSystemSheet(excel, metadata);
                    if (verbose) { System.out.println(RB.Tools.text("updateCommands")); }

                    scanInvalidCommands(excel, metadata);
                    if (verbose) { System.out.println(RB.Tools.text("inspectedScript")); }

                    findProblematicNames(excel, ADDR_COMMAND_START);
                    fixDuplicateActivities(excel, ADDR_COMMAND_START);
                    resetZoomAndStartingPosition(excel, 1, "A5");
                    excel.save();

                } else if (InputFileUtils.isValidMacro(excel)) {
                    System.out.println("processing " + filePath);

                    handleMacroSystemSheet(excel, metadata);
                    if (verbose) { System.out.println(RB.Tools.text("updateCommands")); }

                    scanInvalidMacroCommands(excel, metadata);
                    if (verbose) { System.out.println(RB.Tools.text("inspectedMacro")); }

                    findProblematicNames(excel, ADDR_MACRO_COMMAND_START);
                    fixDuplicateActivities(excel, ADDR_MACRO_COMMAND_START);
                    resetZoomAndStartingPosition(excel, 1, "A2");
                    excel.save();
                } else {
                    // remove system sheet, if found...
                    Worksheet worksheet = excel.worksheet(SHEET_SYSTEM);
                    if (worksheet != null) {
                        if (verbose) { System.out.println(RB.Tools.text("removeSystemSheet", fileName)); }

                        // XSSFWorkbook workbook = workbook;
                        for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
                            if (StringUtils.equals(workbook.getSheetAt(i).getSheetName(), SHEET_SYSTEM)) {
                                System.out.println(RB.Tools.text("deleteSystemSheet", i, file.getAbsolutePath()));
                                workbook.removeSheetAt(i);
                                Excel.save(file, workbook);
                                break;
                            }
                        }
                    }

                    // could be a plan of old (v2) format...
                    List<Worksheet> v2Plans = InputFileUtils.retrieveV2Plan(excel);
                    if (CollectionUtils.isEmpty(v2Plans)) {
                        if (verbose) { System.out.println(RB.Tools.text("notNexialScript", filePath)); }
                    } else {
                        System.out.println("processing " + filePath);
                        v2Plans.forEach(plan -> {
                            if (!updateV2Plan(plan)) {
                                System.err.println(RB.Tools.text("errorUpdatePlan", plan.getName(), filePath));
                            }
                        });

                        resetZoomAndStartingPosition(excel, 0, "A2");
                        excel.save();
                    }
                }
            } catch (Exception e) {
                System.err.println(RB.Tools.text("error.parse", file, e.getMessage()));
            }
        });
    }

    protected boolean updateTemplate(Excel excel) throws IOException {
        boolean[] updated = new boolean[]{false};

        // find all existing worksheet (minus system sheet)
        XSSFWorkbook workbook = excel.getWorkbook();
        int sheetCount = workbook.getNumberOfSheets();
        for (int i = 0; i < sheetCount; i++) {
            XSSFSheet sheet = workbook.getSheetAt(i);
            String sheetName = sheet.getSheetName();
            if (StringUtils.equals(sheetName, SHEET_SYSTEM)) { continue; }

            MDC.put("script.file", excel.getFile().getName());
            MDC.put("script.scenario", sheetName);

            // start from row 5, scan for each command
            if (verbose) { System.out.println("\tinspecting " + sheetName); }

            Worksheet worksheet = excel.worksheet(sheetName);

            // probably no one's using V1 anymore... so commented out
            if (InputFileUtils.isV1Script(worksheet) || InputFileUtils.isV15Script(worksheet)) {
                worksheet.firstCell(ADDR_HEADER_TEST_STEP).setCellValue(HEADER_ACTIVITY);
                worksheet.firstCell(new ExcelAddress("" + COL_TARGET +
                                                     (ADDR_HEADER_TEST_STEP.getRowStartIndex() + COL_IDX_TARGET - 1)))
                         .setCellValue(HEADER_COMMAND_TYPE);
                worksheet.firstCell(new ExcelAddress("J4")).setCellValue(HEADER_TEST_STEP_FLOW_CONTROLS);
                updated[0] = true;
            }

            if (InputFileUtils.isV2Script(worksheet)) {
                worksheet.setColumnValues(ADDR_HEADER_SCENARIO_INFO2, Arrays.asList(HEADER_SCENARIO_INFO_PROJECT,
                                                                                    HEADER_SCENARIO_INFO_RELEASE,
                                                                                    HEADER_SCENARIO_INFO_FEATURE,
                                                                                    HEADER_SCENARIO_INFO_TESTREF,
                                                                                    HEADER_SCENARIO_INFO_AUTHOR));
                updated[0] = true;
            }
        }

        if (updated[0]) { excel.save(); }

        return updated[0];
    }

    protected boolean updateV2Plan(Worksheet plan) {
        XSSFSheet sheet = plan.getSheet();
        String sheetName = sheet.getSheetName();
        if (verbose) { System.out.println(RB.Tools.text("updateTestPlan", sheetName)); }

        plan.setColumnValues(ADD_PLAN_HEADER_FEATURE_AND_TEST,
                             Arrays.asList(PLAN_HEADER_FEATURE_OVERRIDE, PLAN_HEADER_TESTREF_OVERRIDE));

        sheet.setActiveCell(new CellAddress("A5"));
        sheet.setZoom(100);
        return true;
    }

    private void resetZoomAndStartingPosition(Excel excel, int firstTab, String startingPosition) {
        XSSFWorkbook workbook = excel.getWorkbook();

        // reset zoom and starting position
        excel.getWorksheetsStartWith("").forEach(worksheet -> {
            if (!StringUtils.equals(worksheet.getName(), SHEET_SYSTEM)) {
                XSSFSheet sheet = worksheet.getSheet();
                // System.out.println("\t[" + worksheet.getName() + "] setting starting position as A5 and reset zoom to 100%");
                sheet.setActiveCell(new CellAddress(startingPosition));
                sheet.setZoom(100);
            }
        });

        workbook.setActiveSheet(firstTab);
        workbook.setFirstVisibleTab(firstTab);
        workbook.setSelectedTab(firstTab);
    }

    private static void initOptions() {
        cmdOptions.addOption(OPT_VERBOSE);
        cmdOptions.addOption(newArgOption("t", "target", RB.Tools.text("cli.target"), true));
        cmdOptions.addOption(newNonArgOption("u", "unique", RB.Tools.text("cli.autocorrect"), false));
    }

    private void handleMacroSystemSheet(Excel excel, ScriptMetadata metadata) throws IOException {
        updateSystemSheet(excel, metadata, true);
    }

    private void handleSystemSheet(Excel excel, ScriptMetadata metadata) throws IOException {
        updateSystemSheet(excel, metadata, false);
    }

    private void updateSystemSheet(Excel excel, ScriptMetadata metadata, boolean forMacro) throws IOException {
        List<String> targets = metadata.getTargets();
        List<String> headers = new ArrayList<>(targets);
        headers.add(0, "target");

        List<Commands> commands = metadata.getCommands();
        List<NamedRange> names = metadata.getNames();

        Worksheet worksheet = excel.worksheet(SHEET_SYSTEM, true);
        worksheet.clearAllContent();

        worksheet.setColumnValues(new ExcelAddress("A1"), headers);
        worksheet.setRowValues(new ExcelAddress("A2"), targets);

        for (int i = 0; i < commands.size(); i++) {
            List<String> commandList = commands.get(i).getCommands();
            if (forMacro) {
                List<String> macroCommandList = new ArrayList<>(commandList);
                for (int j = 0; j < macroCommandList.size(); j++) {
                    String command = macroCommandList.get(j);
                    if (getNonMacroCommands().contains(command)) {
                        macroCommandList.remove(command);
                        j--;
                    }
                }
                commandList = macroCommandList;
            }

            String columnIndex = ExcelAddress.toLetterCellRef(('B' - 'A') + 1 + i);
            worksheet.setRowValues(new ExcelAddress(columnIndex + "2"), commandList);
        }

        names.forEach(namedRange -> worksheet.createName(namedRange.getName(), namedRange.getReference()));
        //worksheet.show();
        worksheet.hide();

        excel.save();
    }

    private void scanInvalidMacroCommands(Excel excel, ScriptMetadata metadata) {
        scanInvalidCommands(excel, metadata, ADDR_MACRO_COMMAND_START);
    }

    private void scanInvalidCommands(Excel excel, ScriptMetadata metadata) {
        scanInvalidCommands(excel, metadata, ADDR_COMMAND_START);
    }

    private void scanInvalidCommands(Excel excel, ScriptMetadata metadata, ExcelAddress commandStartAddress) {
        List<String> targetCommands = new ArrayList<>();

        List<String> targets = metadata.getTargets();
        List<Commands> allCommands = metadata.getCommands();
        for (int j = 0; j < allCommands.size(); j++) {
            String target = targets.get(j);
            allCommands.get(j).getCommands().forEach(cmd -> targetCommands.add(target + "." + cmd));
        }

        boolean excelUpdated = false;

        // find all existing worksheet (minus system sheet)
        XSSFWorkbook workbook = excel.getWorkbook();
        int sheetCount = workbook.getNumberOfSheets();
        for (int i = 0; i < sheetCount; i++) {
            XSSFSheet sheet = workbook.getSheetAt(i);
            String sheetName = sheet.getSheetName();
            if (StringUtils.equals(sheetName, SHEET_SYSTEM)) { continue; }

            MDC.put("script.file", excel.getFile().getName());
            MDC.put("script.scenario", sheetName);

            Worksheet worksheet = excel.worksheet(sheetName);
            int lastCommandRow = worksheet.findLastDataRow(commandStartAddress);
            String commandAreaAddress = "" + COL_TEST_CASE + (commandStartAddress.getRowStartIndex() + 1) + ":" +
                                        COL_CAPTURE_SCREEN + lastCommandRow;
            ExcelArea area = new ExcelArea(worksheet, new ExcelAddress(commandAreaAddress), false);

            for (int j = 0; j < area.getWholeArea().size(); j++) {
                List<XSSFCell> row = area.getWholeArea().get(j);
                int rowIndex = row.get(0).getRowIndex() + 1;
                MDC.put("script.position", "row " + rowIndex);

                // form the command signature
                XSSFCell cellTarget = row.get(COL_IDX_TARGET);
                String target = Excel.getCellValue(cellTarget);
                if (!targets.contains(target)) {
                    System.err.println(RB.Tools.text("error.cmdTarget", target));
                    continue;
                }

                XSSFCell cellCommand = row.get(COL_IDX_COMMAND);
                String command = Excel.getCellValue(cellCommand);
                if (StringUtils.isBlank(command)) {
                    System.err.println(RB.Tools.text("error.cmd", command));
                    continue;
                }

                String targetCommand = target + "." + command;

                // check for auto-substitution
                if (getReplacedCommands().containsKey(targetCommand)) {
                    // found old command, let's replace it with new one
                    String newCommand = getReplacedCommands().get(targetCommand);
                    cellTarget.setCellValue(StringUtils.substringBeforeLast(newCommand, "."));
                    cellCommand.setCellValue(StringUtils.substringAfterLast(newCommand, "."));
                    targetCommand = newCommand;
                    excelUpdated = true;
                }

                String commandDisplay = target + " » " + command;

                // check for removed commands
                Map<String, String> removedWarnings = getRemovedCommandWarnings();
                if (removedWarnings.containsKey(targetCommand)) {
                    System.err.println(RB.Tools.text("error.removedCommand",
                                                     rowIndex, commandDisplay, removedWarnings.get(targetCommand)));
                }

                // check for warning/suggest
                Map<String, String> deprecatedWarnings = getDeprecatedCommandWarnings();
                if (deprecatedWarnings.containsKey(targetCommand)) {
                    System.err.println(RB.Tools.text("warning.deprecated",
                                                     rowIndex, commandDisplay, deprecatedWarnings.get(targetCommand)));
                }

                String commandSignature = targetCommand;
                Optional<String> matchedCommand = targetCommands.stream()
                                                                .filter(s -> s.startsWith(commandSignature))
                                                                .findFirst();
                if (!matchedCommand.isPresent()) {
                    // for every unknown command, spit out an error
                    // System.err.println("\tInvalid command:\t" + commandDisplay);
                    continue;
                }

                // check param count

                // special case: some commands will automatically fill missing/undefined cells during the call
                if (autoFillParamCommands.contains(StringUtils.substringBeforeLast(targetCommand, "("))) { continue; }

                List<String> paramList =
                    TextUtils.toList(StringUtils.substringBetween(matchedCommand.get(), "(", ")"), ",", true);
                int paramCount = CollectionUtils.size(paramList);

                List<String> paramValues = TestStep.readParamValues(row);
                int paramValuesCount = CollectionUtils.size(paramValues);
                if (paramValuesCount != paramCount) {
                    System.err.println(RB.Tools.text("error.paramMismatch",
                                                     commandDisplay, paramCount, paramValuesCount));
                }

                for (int k = 0; k < paramCount; k++) {
                    if (StringUtils.isBlank(Excel.getCellValue(row.get(COL_IDX_PARAMS_START + k)))) {
                        System.err.println(RB.Tools.text("error.noParam",
                                                         commandDisplay, IterableUtils.get(paramList, k)));
                    }
                }
            }
        }
    }

    private void findProblematicNames(Excel excel, ExcelAddress commandStartAddress) {
        String filename = excel.getFile().getName();

        // find all existing worksheet (minus system sheet)
        excel.getWorksheetsStartWith("").forEach(sheet -> {
            String sheetName = sheet.getName();
            if (StringUtils.equals(sheetName, SHEET_SYSTEM)) { return; }

            String sheetNameTrimmed = StringUtils.trim(sheetName);
            if (!StringUtils.equals(sheetName, sheetNameTrimmed)) {
                System.err.println(RB.Fatal.text("problematicName", "\t[" + filename + "]:", "sheet", sheetName));
                System.out.printf("\t[%s]: fixing sheet name now...\n\n", filename);
                sheet.setSheetName(sheetNameTrimmed);
            }

            String commandAreaAddress = "" + COL_TEST_CASE + (commandStartAddress.getRowStartIndex() + 1) + ":" +
                                        COL_CAPTURE_SCREEN + sheet.findLastDataRow(commandStartAddress);
            ExcelArea area = new ExcelArea(sheet, new ExcelAddress(commandAreaAddress), false);
            for (int j = 0; j < area.getWholeArea().size(); j++) {
                List<XSSFCell> row = area.getWholeArea().get(j);

                XSSFCell cellActivity = row.get(COL_IDX_TESTCASE);

                String activityName = Excel.getCellValue(cellActivity);
                if (StringUtils.isEmpty(activityName)) { continue; }

                // detect non-printable characters in activity name -- WARN
                String activityNameTrimmed = StringUtils.trim(activityName);
                if (!StringUtils.equals(activityName, activityNameTrimmed)) {
                    String cellAddress = cellActivity.getAddress().formatAsString();
                    System.err.println(RB.Fatal.text("problematicName",
                                                     "\t[" + cellAddress + "]:", "activity", activityName));
                    System.out.printf("\t[%s]: fixing activity name now...\n\n", cellAddress);
                    cellActivity.setCellValue(activityNameTrimmed);
                }
            }
        });
    }

    private void fixDuplicateActivities(Excel excel, ExcelAddress commandStartAddress) {
        if (!fixDuplicateActivity) { return; }

        Map<String, String> activityNames = new HashMap<>();

        // find all existing worksheet (minus system sheet)
        excel.getWorksheetsStartWith("").forEach(sheet -> {
            String sheetName = sheet.getName();
            if (StringUtils.equals(sheetName, SHEET_SYSTEM)) { return; }

            String commandAreaAddress = "" + COL_TEST_CASE + (commandStartAddress.getRowStartIndex() + 1) + ":" +
                                        COL_CAPTURE_SCREEN + sheet.findLastDataRow(commandStartAddress);
            ExcelArea area = new ExcelArea(sheet, new ExcelAddress(commandAreaAddress), false);
            for (int j = 0; j < area.getWholeArea().size(); j++) {
                List<XSSFCell> row = area.getWholeArea().get(j);

                XSSFCell cellActivity = row.get(COL_IDX_TESTCASE);

                String activityName = Excel.getCellValue(cellActivity);
                if (StringUtils.isEmpty(activityName)) { continue; }

                String cellAddress = cellActivity.getAddress().formatAsString();
                String newActivityName = findNewActivityName(activityNames, activityName);
                activityNames.put(newActivityName, cellAddress);

                // check for activity name duplicates
                if (!StringUtils.equals(activityName, newActivityName)) {
                    System.out.println(RB.Tools.text("activity.dupRenamed",
                                                     cellAddress, activityName, newActivityName));
                    cellActivity.setCellValue(newActivityName);
                }
            }
        });
    }

    /**
     * either return {@literal activity} as is - if it is a unique name, or rename it to make it unique. The naming
     * strategy is to add a number to the end of the existing activity name.
     */
    private String findNewActivityName(Map<String, String> existingNames, String activity) {
        if (!existingNames.containsKey(activity)) { return activity; }

        List<String> parts = RegexUtils.collectGroups(activity, "(.+)(\\d+)");

        // activity name doesn't end with number, let's add one
        // if not, let's increment last number by 1
        return findNewActivityName(existingNames,
                                   CollectionUtils.size(parts) != 2 ?
                                   StringUtils.appendIfMissing(activity, " ") + "1" :
                                   parts.get(0) + (NumberUtils.toInt(parts.get(1)) + 1));
    }
}
