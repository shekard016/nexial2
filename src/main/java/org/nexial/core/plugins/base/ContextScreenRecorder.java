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

package org.nexial.core.plugins.base;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.nexial.core.model.ExecutionContext;
import org.nexial.core.model.TestStep;
import org.nexial.core.utils.ConsoleUtils;
import org.nexial.core.utils.OutputFileUtils;
import org.nexial.core.variable.Syspath;

import java.awt.*;
import java.io.File;
import java.io.IOException;

import static java.io.File.separator;
import static org.nexial.core.NexialConst.Project.appendCapture;
import static org.nexial.core.NexialConst.Recording.*;
import static org.nexial.core.NexialConst.Recording.Types.avi;
import static org.nexial.core.NexialConst.Recording.Types.mp4;
import static org.nexial.core.SystemVariables.getDefault;
import static org.nexial.core.SystemVariables.getDefaultBool;

/**
 * main delegate for various screen recording strategies
 */
public class ContextScreenRecorder {
    protected ExecutionContext context;
    protected ScreenRecorder screenRecorder;
    protected boolean isVideoRunning = false;
    protected String fileExt;
    protected String startingLocation;
    protected String videoFile;
    protected String videoTitle;

    public void setContext(ExecutionContext context) { this.context = context; }

    public boolean isVideoRunning() { return isVideoRunning; }

    public boolean isRecordingEnabled() { return isRecordingEnabled(context); }

    public static boolean isRecordingEnabled(ExecutionContext context) {
        return context.getBooleanData(RECORDING_ENABLED, getDefaultBool(RECORDING_ENABLED));
    }

    public String getVideoFile() { return videoFile; }

    public void start(TestStep startsFrom) throws IOException {
        sanityCheck();

        if (startsFrom == null) {
            screenRecorder.start();
            videoFile = screenRecorder.getVideoFile();
            isVideoRunning = true;
            return;
        }

        String targetDir = appendCapture(new Syspath().out("fullpath")) + separator;
        FileUtils.forceMkdir(new File(targetDir));
        videoFile = targetDir + OutputFileUtils.generateOutputFilename(startsFrom, fileExt);
        startingLocation = "ROW " + (startsFrom.getRow().get(0).getRowIndex() + 1);
        ConsoleUtils.log(startingLocation, "start recording to '" + videoFile + "'");

        videoTitle = context.getTestScript().getFile().getName() + " : " + context.getCurrentTestStep().toString();

        screenRecorder.setTitle(videoTitle);
        screenRecorder.start(videoFile);
        isVideoRunning = true;
    }

    public void stop() throws IOException {
        // so the stop won't feel so sudden
        try { Thread.sleep(1000); } catch (InterruptedException e) { }

        screenRecorder.stop();
        isVideoRunning = false;

        // if (videoFile != null && context != null) {
        //     String link = context.isOutputToCloud() ? context.getOtc().importMedia(new File(videoFile)) : videoFile;
        //     TestStep currentTestStep = context.getCurrentTestStep();
        //     if (currentTestStep != null) {
        //         currentTestStep.addNestedScreenCapture(link, "recording from " + startingLocation + " stopped");
        //     }
        //     ConsoleUtils.log("recording saved and is accessible via " + link);
        // }
    }

    public static ContextScreenRecorder newInstance(ExecutionContext context) throws AWTException, IOException {
        if (context == null) { throw new IllegalArgumentException("execution context is null!"); }

        ContextScreenRecorder self = new ContextScreenRecorder();
        self.context = context;

        String recorderType = context.getStringData(RECORDER_TYPE, getDefault(RECORDER_TYPE));
        if (StringUtils.equals(recorderType, mp4.name())) {
            self.screenRecorder = new Mp4ScreenRecorder();
            self.fileExt = mp4.name();
            return self;
        }

        if (StringUtils.equals(recorderType, avi.name())) {
            self.screenRecorder = new AviScreenRecorder();
            self.fileExt = avi.name();
            return self;
        }

        throw new IllegalArgumentException("Unknown record type:" + recorderType);
    }

    private void sanityCheck() {
        if (screenRecorder == null) { throw new RuntimeException("No screen recorder is active and available"); }
    }
}
