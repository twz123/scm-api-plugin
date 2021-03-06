/*
 * The MIT License
 *
 * Copyright 2016 CloudBees, Inc.
 *
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
 */

package jenkins.scm.impl;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import jenkins.scm.api.mixin.ChangeRequestSCMHead;
import jenkins.scm.api.SCMHead;
import jenkins.scm.api.SCMHeadCategory;
import jenkins.scm.api.SCMSource;
import org.jvnet.localizer.Localizable;

/**
 * Standard category for {@link SCMHead} instances that implement {@link ChangeRequestSCMHead}.
 *
 * @since 2.0
 */
public final class ChangeRequestSCMHeadCategory extends SCMHeadCategory {
    /**
     * The {@link ChangeRequestSCMHeadCategory} singleton with the default naming.
     */
    public static final ChangeRequestSCMHeadCategory DEFAULT = new ChangeRequestSCMHeadCategory();

    /**
     * Constructs a {@link ChangeRequestSCMHeadCategory} using the default naming.
     */
    private ChangeRequestSCMHeadCategory() {
        super("change-requests", Messages._ChangeRequestSCMHeadCategory_DisplayName());
    }

    /**
     * Constructs a {@link ChangeRequestSCMHeadCategory} with customized naming. Use this constructor when the generic
     * naming is not appropriate terminology for the specific {@link SCMSource}'s naming of change requests.
     *
     * @param displayName the display name for change requests.
     */
    @SuppressFBWarnings("NP_METHOD_PARAMETER_TIGHTENS_ANNOTATION")
    public ChangeRequestSCMHeadCategory(@NonNull Localizable displayName) {
        super("change-requests", displayName);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isMatch(@NonNull SCMHead instance) {
        return instance instanceof ChangeRequestSCMHead;
    }
}
