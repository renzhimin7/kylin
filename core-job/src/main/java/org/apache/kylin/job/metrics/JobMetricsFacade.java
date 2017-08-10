/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
*/

package org.apache.kylin.job.metrics;

import org.apache.kylin.metrics.lib.impl.RecordEvent;
import org.apache.kylin.metrics.property.JobPropertyEnum;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JobMetricsFacade {
    private static final Logger logger = LoggerFactory.getLogger(JobMetricsFacade.class);

    public static void setJobWrapper(RecordEvent metricsEvent, String projectName, String cubeName, String jobId,
            String jobType, String cubingType) {
        metricsEvent.put(JobPropertyEnum.PROJECT.toString(), projectName);
        metricsEvent.put(JobPropertyEnum.CUBE.toString(), cubeName);
        metricsEvent.put(JobPropertyEnum.ID_CODE.toString(), jobId);
        metricsEvent.put(JobPropertyEnum.TYPE.toString(), jobType);
        metricsEvent.put(JobPropertyEnum.ALGORITHM.toString(), cubingType);
    }

    public static void setJobStats(RecordEvent metricsEvent, long tableSize, long cubeSize, long buildDuration,
            long waitResourceTime, double perBytesTimeCost) {
        metricsEvent.put(JobPropertyEnum.SOURCE_SIZE.toString(), tableSize);
        metricsEvent.put(JobPropertyEnum.CUBE_SIZE.toString(), cubeSize);
        metricsEvent.put(JobPropertyEnum.BUILD_DURATION.toString(), buildDuration);
        metricsEvent.put(JobPropertyEnum.WAIT_RESOURCE_TIME.toString(), waitResourceTime);
        metricsEvent.put(JobPropertyEnum.PER_BYTES_TIME_COST.toString(), perBytesTimeCost);
    }

    public static void setJobStepStats(RecordEvent metricsEvent, long dColumnDistinct, long dDictBuilding,
            long dCubingInmem, long dHfileConvert) {
        metricsEvent.put(JobPropertyEnum.STEP_DURATION_DISTINCT_COLUMNS.toString(), dColumnDistinct);
        metricsEvent.put(JobPropertyEnum.STEP_DURATION_DICTIONARY.toString(), dDictBuilding);
        metricsEvent.put(JobPropertyEnum.STEP_DURATION_INMEM_CUBING.toString(), dCubingInmem);
        metricsEvent.put(JobPropertyEnum.STEP_DURATION_HFILE_CONVERT.toString(), dHfileConvert);
    }

    public static <T extends Throwable> void setJobExceptionWrapper(RecordEvent metricsEvent, String projectName,
            String cubeName, String jobId, String jobType, String cubingType, Class<T> throwableClass) {
        setJobWrapper(metricsEvent, projectName, cubeName, jobId, jobType, cubingType);
        metricsEvent.put(JobPropertyEnum.EXCEPTION.toString(), throwableClass.getName());
    }
}