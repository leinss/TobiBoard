// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard

/**
 * Marks the UI flow suite (`make test-ui`).
 *
 * The other instrumentation tests load native STT models and take minutes; the runner selects on
 * this annotation so the UI suite can be run on its own. A package filter cannot do the job: the
 * IME flow test has to live in `helium314.keyboard.latin`, which contains the model tests as a
 * subpackage.
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
annotation class UiFlowTest
