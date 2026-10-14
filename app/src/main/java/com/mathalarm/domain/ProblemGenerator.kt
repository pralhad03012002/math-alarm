package com.mathalarm.domain

import com.mathalarm.data.Difficulty
import kotlin.random.Random

/**
 * The shape of an arithmetic question. Carries its operands, not just its rendered text, so the
 * generator's rules can be *verified* on a generated problem rather than asserted in a comment —
 * see [ProblemGenerator.isWellFormed].
 *
 * [text] is also the identity used for the session's no-repeat memory (`3 + 4` and `4 + 3` are
 * deliberately different problems: the digits you read are what you have to think about).
 */
data class Problem(
    val operation: Operation,
    val operands: List<Int>,
    val answer: Int,
) {
    val text: String = when (operation) {
        Operation.ADD -> "${operands[0]} + ${operands[1]}"
        Operation.SUBTRACT -> "${operands[0]} - ${operands[1]}"
        Operation.MULTIPLY -> "${operands[0]} × ${operands[1]}"
        Operation.DIVIDE -> "${operands[0]} ÷ ${operands[1]}"
        Operation.MULTIPLY_ADD -> "${operands[0]} × ${operands[1]} + ${operands[2]}"
        Operation.MULTIPLY_SUBTRACT -> "${operands[0]} × ${operands[1]} - ${operands[2]}"
    }
}

/** Which arithmetic form a [Problem] takes. Nothing here is multiple choice; the answer is typed. */
enum class Operation { ADD, SUBTRACT, MULTIPLY, DIVIDE, MULTIPLY_ADD, MULTIPLY_SUBTRACT }

/**
 * Generates the arithmetic that stands between a sleeping person and a silent phone.
 *
 * **Pure Kotlin — no Android imports** (BUILD_SPECS §3), so every rule below is unit-testable on
 * the JVM exactly like [NextTrigger]. Randomness is always injected as a [Random], never taken
 * from a global, so a test can pin an entire session.
 *
 * Two layers of enforcement, on purpose:
 *
 *  1. **Construction.** Each tier builds its operands so that the tier's rules hold by arithmetic —
 *     a MEDIUM addition picks its units digits as `ua ∈ 1..9` and `ub ∈ (10 - ua)..9`, so the carry
 *     is not hoped for, it is forced.
 *  2. **Verification.** [generate] re-checks every candidate through [isWellFormed] before handing
 *     it out. That predicate is the executable statement of BRAINSTORM §4.2 — it decides MEDIUM by
 *     the *presence of a carry or borrow*, not by digit count, so `20 + 30` fails it. A generator
 *     bug therefore cannot reach the user as a negative answer, a non-integer division, or a
 *     two-digit sum that needs no thought.
 */
object ProblemGenerator {

    /**
     * How many candidates [generate] will draw before it accepts a repeat. Only reachable when
     * `exclude` has swallowed most of a tier's problem space, which takes hundreds of consecutive
     * wrong answers in one session.
     */
    private const val MAX_ATTEMPTS = 300

    // ------------------------------------------------------------------ rules

    /**
     * BRAINSTORM §4.2's degenerate-factor rule, as code. `× 0` and `× 1` are not arithmetic, and
     * `× 10` is digit-shuffling rather than thinking.
     *
     * The `n % 10 != 0` clause is what rejects `× 10`, and it also rejects `× 20`, `× 30` … for
     * exactly the same reason — `30 × 7` is `3 × 7` with a zero typed after it, which is the
     * reflex this module exists to defeat.
     */
    fun isAllowedFactor(n: Int): Boolean = n != 0 && n != 1 && n % 10 != 0

    /**
     * True when adding the units digits of [a] and [b] actually carries into the tens.
     *
     * This is the test that makes `47 + 28` MEDIUM and `20 + 30` not, and it is deliberately about
     * the digits rather than the operand magnitudes.
     */
    fun hasCarry(a: Int, b: Int): Boolean = (a % 10) + (b % 10) >= 10

    /** True when subtracting the units digit of [b] from that of [a] has to borrow from the tens. */
    fun hasBorrow(a: Int, b: Int): Boolean = (a % 10) < (b % 10)

    private fun isTwoDigit(n: Int): Boolean = n in 10..99

    /**
     * Every rule in BUILD_SPECS §3 / BRAINSTORM §4.2, evaluated against a concrete [problem].
     *
     * Universal: the answer is a positive integer and matches the operands exactly (so a division
     * is only well-formed when it divides evenly), and no multiplication or division uses a
     * degenerate factor.
     *
     * Per tier: EASY forbids identical operands and keeps inside the spec's single-digit ranges;
     * MEDIUM requires a real carry / real borrow on its two-digit forms; HARD requires a genuine
     * two-digit factor, a two-step form, or a clean division.
     */
    fun isWellFormed(problem: Problem, difficulty: Difficulty): Boolean {
        val n = problem.operands
        if (problem.answer <= 0) return false
        if (n.any { it < 0 }) return false

        // The answer must be the arithmetic, not a stored guess.
        val arithmeticHolds = when (problem.operation) {
            Operation.ADD -> n.size == 2 && n[0] + n[1] == problem.answer
            Operation.SUBTRACT -> n.size == 2 && n[0] - n[1] == problem.answer
            Operation.MULTIPLY -> n.size == 2 && n[0] * n[1] == problem.answer
            Operation.DIVIDE ->
                n.size == 2 && n[1] != 0 && n[0] % n[1] == 0 && n[0] / n[1] == problem.answer
            Operation.MULTIPLY_ADD -> n.size == 3 && n[0] * n[1] + n[2] == problem.answer
            Operation.MULTIPLY_SUBTRACT -> n.size == 3 && n[0] * n[1] - n[2] == problem.answer
        }
        if (!arithmeticHolds) return false

        // No ×0, ×1, ×10 anywhere a factor or divisor appears. No +0 / -0 either: an additive
        // operand of zero is the same kind of non-problem.
        //
        // A division's factors are its **divisor and its quotient**, not its dividend (review
        // M2-1.4): `20 ÷ 4 = 5` is a perfectly good problem, and applying the degenerate-factor
        // rule to the 20 threw away 8 of the 72 constructible divisor/quotient pairs. The quotient
        // side is checked in the HARD branch below, which is the only tier that allows a division
        // at all.
        val factorsAllowed = when (problem.operation) {
            Operation.ADD, Operation.SUBTRACT -> n[0] > 0 && n[1] > 0
            Operation.MULTIPLY -> isAllowedFactor(n[0]) && isAllowedFactor(n[1])
            Operation.DIVIDE -> isAllowedFactor(n[1])
            Operation.MULTIPLY_ADD, Operation.MULTIPLY_SUBTRACT ->
                isAllowedFactor(n[0]) && isAllowedFactor(n[1]) && n[2] > 0
        }
        if (!factorsAllowed) return false

        return when (difficulty) {
            Difficulty.EASY -> when (problem.operation) {
                // a + b, a,b ∈ 2..9, and never a + a.
                Operation.ADD -> n[0] in 2..9 && n[1] in 2..9 && n[0] != n[1]
                // a - b, a ∈ 5..18, b < a. b ≥ 2 keeps `- 0` and `- 1` out, the additive twin of
                // the `+ 0` rule; `b < a` is what guarantees the positive result.
                Operation.SUBTRACT -> n[0] in 5..18 && n[1] in 2 until n[0]
                else -> false
            }

            Difficulty.MEDIUM -> when (problem.operation) {
                // Two-digit addition *with a carry* — digit count alone is not enough.
                Operation.ADD -> isTwoDigit(n[0]) && isTwoDigit(n[1]) && hasCarry(n[0], n[1])
                // Two-digit subtraction *with a borrow*, larger first so the result is positive.
                Operation.SUBTRACT ->
                    isTwoDigit(n[0]) && isTwoDigit(n[1]) && n[0] > n[1] && hasBorrow(n[0], n[1])
                // Times tables: a,b ∈ 3..12, minus the degenerate 10 already rejected above.
                Operation.MULTIPLY -> n[0] in 3..12 && n[1] in 3..12
                else -> false
            }

            Difficulty.HARD -> when (problem.operation) {
                // ab × c: one genuinely two-digit factor against a single digit.
                Operation.MULTIPLY -> isTwoDigit(n[0]) && n[1] in 2..9
                // Two-step. The subtracting form must still land above zero, which the universal
                // `answer > 0` check above already enforces.
                Operation.MULTIPLY_ADD, Operation.MULTIPLY_SUBTRACT ->
                    n[0] in 3..12 && n[1] in 3..12 && n[2] in 2..20
                // Clean division. Integrality and `dividend == divisor × quotient` are the
                // universal checks above; here the **quotient** is held to the degenerate-factor
                // rule, which is what actually stops `96 ÷ 96 = 1`, `12 ÷ 12 = 1` and `80 ÷ 8 = 10`
                // — the old line only claimed to (review M2-1.4) and in fact checked nothing but
                // the dividend's magnitude, so all three of those passed. The dividend requirement
                // is stated as what it is, "at least two digits" (`9 ÷ 3` is not a HARD problem),
                // rather than as a hard-coded `100..144` window that would start rejecting valid
                // problems the moment `SMALL_FACTORS` went past 12.
                Operation.DIVIDE -> isAllowedFactor(problem.answer) && n[0] >= 10
                else -> false
            }
        }
    }

    // ------------------------------------------------------------- generation

    /**
     * One problem for [difficulty], drawn from [random], whose [Problem.text] is not in [exclude].
     *
     * [exclude] is how "no repeated problem within one alarm session" (BRAINSTORM §4.2) is
     * implemented: [ChallengeState] passes every text it has already asked. If the tier's problem
     * space is genuinely exhausted — hundreds of wrong answers at EASY, whose space is a few
     * hundred problems — this returns a repeat rather than looping or failing. A repeated problem
     * is a far better outcome than an alarm with no problem to solve.
     */
    fun generate(
        difficulty: Difficulty,
        random: Random,
        exclude: Set<String> = emptySet(),
    ): Problem {
        var verified: Problem? = null
        repeat(MAX_ATTEMPTS) {
            val candidate = draw(difficulty, random)
            if (!isWellFormed(candidate, difficulty)) return@repeat
            if (candidate.text !in exclude) return candidate
            verified = candidate
        }
        return verified ?: draw(difficulty, random)
    }

    private fun draw(difficulty: Difficulty, random: Random): Problem = when (difficulty) {
        Difficulty.EASY ->
            if (random.nextBoolean()) easyAdd(random) else easySubtract(random)

        Difficulty.MEDIUM -> when (random.nextInt(3)) {
            0 -> mediumAddWithCarry(random)
            1 -> mediumSubtractWithBorrow(random)
            else -> mediumTimesTable(random)
        }

        Difficulty.HARD -> when (random.nextInt(4)) {
            0 -> hardTwoDigitTimesOne(random)
            1 -> hardTwoStep(random, adding = true)
            2 -> hardTwoStep(random, adding = false)
            else -> hardCleanDivision(random)
        }
    }

    // ------------------------------------------------------------------- easy

    /** `a + b`, a,b ∈ 2..9. [b] is drawn from the range with [a] removed, so `a + a` is impossible. */
    private fun easyAdd(random: Random): Problem {
        val a = random.nextInt(2, 10)
        val others = (2..9).filter { it != a }
        val b = others[random.nextInt(others.size)]
        return Problem(Operation.ADD, listOf(a, b), a + b)
    }

    /** `a - b`, a ∈ 5..18, b ∈ 2 until a. `b < a` forces a positive result by construction. */
    private fun easySubtract(random: Random): Problem {
        val a = random.nextInt(5, 19)
        val b = random.nextInt(2, a)
        return Problem(Operation.SUBTRACT, listOf(a, b), a - b)
    }

    // ----------------------------------------------------------------- medium

    /**
     * Two-digit addition with a guaranteed carry.
     *
     * The units digits are drawn as a dependent pair — `ub` starts at `10 - ua` — so `ua + ub ≥ 10`
     * is arithmetic, not luck. Nothing here can produce the `20 + 30` that BRAINSTORM §4.2 calls
     * out, because `ua ≥ 1` and `ub ≥ 10 - ua ≥ 1`.
     */
    private fun mediumAddWithCarry(random: Random): Problem {
        val unitsA = random.nextInt(1, 10)
        val unitsB = random.nextInt(10 - unitsA, 10)
        val a = random.nextInt(1, 10) * 10 + unitsA
        val b = random.nextInt(1, 10) * 10 + unitsB
        return Problem(Operation.ADD, listOf(a, b), a + b)
    }

    /**
     * Two-digit subtraction with a guaranteed borrow and a positive result.
     *
     * `unitsA < unitsB` forces the borrow; `tensA > tensB` then keeps `a > b` even after the tens
     * column has lent a digit away, so the answer cannot go negative.
     */
    private fun mediumSubtractWithBorrow(random: Random): Problem {
        val unitsA = random.nextInt(0, 9)
        val unitsB = random.nextInt(unitsA + 1, 10)
        val tensA = random.nextInt(2, 10)
        val tensB = random.nextInt(1, tensA)
        val a = tensA * 10 + unitsA
        val b = tensB * 10 + unitsB
        return Problem(Operation.SUBTRACT, listOf(a, b), a - b)
    }

    /** `a × b`, a,b ∈ 3..12 with 10 struck out by [isAllowedFactor]. */
    private fun mediumTimesTable(random: Random): Problem {
        val a = pick(SMALL_FACTORS, random)
        val b = pick(SMALL_FACTORS, random)
        return Problem(Operation.MULTIPLY, listOf(a, b), a * b)
    }

    // ------------------------------------------------------------------- hard

    /** `ab × c`, e.g. `37 × 8`. The two-digit factor is never a multiple of ten. */
    private fun hardTwoDigitTimesOne(random: Random): Problem {
        val ab = pick(TWO_DIGIT_FACTORS, random)
        val c = random.nextInt(3, 10)
        return Problem(Operation.MULTIPLY, listOf(ab, c), ab * c)
    }

    /**
     * `a × b + c` or `a × b - c`.
     *
     * For the subtracting form `c` is capped at `a * b - 1`, so the result is positive by
     * construction rather than by rejection.
     */
    private fun hardTwoStep(random: Random, adding: Boolean): Problem {
        val a = pick(SMALL_FACTORS, random)
        val b = pick(SMALL_FACTORS, random)
        val product = a * b
        val ceiling = if (adding) 20 else minOf(20, product - 1)
        val c = random.nextInt(2, ceiling + 1)
        return if (adding) {
            Problem(Operation.MULTIPLY_ADD, listOf(a, b, c), product + c)
        } else {
            Problem(Operation.MULTIPLY_SUBTRACT, listOf(a, b, c), product - c)
        }
    }

    /**
     * Clean division, built **backwards from a known product** (BUILD_SPECS §3): pick the divisor
     * and the quotient first, then multiply to get the dividend. The remainder is therefore zero by
     * construction — there is no rounding, no rejection sampling, and no way for a non-integer
     * answer to exist. Both the divisor and the quotient come from [SMALL_FACTORS], so neither can
     * be 1 or 10, and the quotient starts at 4 so the dividend is always at least two digits
     * (`9 ÷ 3` is not a HARD problem).
     */
    private fun hardCleanDivision(random: Random): Problem {
        val divisor = pick(SMALL_FACTORS, random)
        val quotient = pick(DIVISION_QUOTIENTS, random)
        return Problem(Operation.DIVIDE, listOf(divisor * quotient, divisor), quotient)
    }

    // ----------------------------------------------------------------- shared

    /** 3..12 with the degenerate 10 removed. */
    private val SMALL_FACTORS: List<Int> = (3..12).filter(::isAllowedFactor)

    /** 4..12 with the degenerate 10 removed: the quotient side of a HARD clean division. */
    private val DIVISION_QUOTIENTS: List<Int> = (4..12).filter(::isAllowedFactor)

    /** Two-digit factors, multiples of ten removed. */
    private val TWO_DIGIT_FACTORS: List<Int> = (12..99).filter(::isAllowedFactor)

    private fun pick(from: List<Int>, random: Random): Int = from[random.nextInt(from.size)]
}
