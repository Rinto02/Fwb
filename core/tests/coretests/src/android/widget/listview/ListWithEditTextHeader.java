/*
 * Copyright (C) 2008 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.widget.listview;

import android.widget.ListScenario;

/**
 * A list view with a single edit text in a header.
 */
public class ListWithEditTextHeader extends ListScenario {

    @Override
    protected void init(Params params) {
        params.setHeaderViewCount(1)
                .setHeaderFocusable(true)
                .setItemsFocusable(true)
                .setNumItems(6)
                .setItemScreenSizeFactor(0.2);
    }
}