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

package org.nexial.core.tools.swagger;

import java.util.List;

/**
 * Represents a scenario in the Nexial Swagger script.
 *
 * @author Dhanapathi Marepalli
 */
public class SwaggerScenario {
    private String name;
    private String description;
    private List<SwaggerActivity> activities;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public List<SwaggerActivity> getActivities() {
        return activities;
    }

    public void setActivities(List<SwaggerActivity> activities) {
        this.activities = activities;
    }

    @Override
    public String toString() {
        return "SwaggerScenario{" +
               "name='" + name + '\'' +
               ", description='" + description + '\'' +
               ", activities=" + activities +
               '}';
    }
}
