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

package org.nexial.core.variable;

import org.junit.Test;
import org.nexial.core.ExcelBasedTests;
import org.nexial.core.model.ExecutionSummary;

public class HeadlessFunctionTests extends ExcelBasedTests {

    @Test
    public void testFormat() throws Exception {
        ExecutionSummary executionSummary = testViaExcel("unitTest_function.xlsx", "format");
        assertPassFail(executionSummary, "format", TestOutcomeStats.allPassed());
    }

    @Test
    public void testDate() throws Exception {
        ExecutionSummary executionSummary = testViaExcel("unitTest_function.xlsx", "date");
        assertPassFail(executionSummary, "date", TestOutcomeStats.allPassed());
    }

    @Test
    public void testProjectFile() throws Exception {
        ExecutionSummary executionSummary = testViaExcel("unitTest_function.xlsx", "projectfile");
        assertPassFail(executionSummary, "projectfile", TestOutcomeStats.allPassed());
    }
}
