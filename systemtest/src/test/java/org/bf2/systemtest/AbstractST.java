package org.bf2.systemtest;

import org.bf2.systemtest.framework.ExtensionContextParameterResolver;
import org.bf2.systemtest.framework.IndicativeSentences;
import org.bf2.systemtest.framework.TestCallbackListener;
import org.bf2.systemtest.framework.TestExceptionCallbackListener;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Base systemtest class which should be derived in every ST class
 * Provides extensions and base callbacks from framework
 * Using this class cause avoiding test duplications
 */

@ExtendWith(TestCallbackListener.class)
@ExtendWith(ExtensionContextParameterResolver.class)
@ExtendWith(TestExceptionCallbackListener.class)
@DisplayNameGeneration(IndicativeSentences.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class AbstractST {
}
