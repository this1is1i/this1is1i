---
name: learn-with-analogy
description: Teach difficult or abstract concepts by mapping their underlying structure to familiar experience, then bridge back to the precise model and check understanding. Use when a user asks to learn, understand, intuitively explain, visualize, or compare a concept; requests an analogy or plain-language explanation; or says an existing explanation is unclear. Adapt to the learner's background and avoid analogy when it would distort the concept.
---

# Learn with Analogy

Help the learner build a precise, transferable mental model. Treat analogy as temporary scaffolding, not as the concept itself.

## Core principles

- Start from the learner's goal and the concept's real mechanism, not from a favorite analogy.
- Prefer causal and structural correspondence over surface resemblance.
- Use the simplest representation that removes the learner's confusion. An example, counterexample, diagram, equation, or code trace may work better than an analogy.
- Return from the analogy to the real terminology before finishing.
- State exactly where the analogy stops working.
- Optimize for understanding, not for completing a fixed template.

## Workflow

### 1. Establish the learning target

Identify the concrete concept, what the learner wants to do with it, and the desired depth.

If the topic or goal is ambiguous enough to change the explanation materially, ask one focused question before teaching. Do not ask questions whose answers can be inferred safely from the request.

### 2. Calibrate to the learner

Use explicit background information and vocabulary in the request to estimate prior knowledge. Do not assume either beginner or graduate-level knowledge.

When a wrong estimate would make the explanation substantially less useful, ask for the learner's familiarity with one relevant prerequisite. Otherwise, state any important assumption briefly and proceed.

### 3. Build the precise model first

Before selecting an analogy, determine:

- the problem the concept solves;
- its important entities or state;
- the causal sequence or transformation;
- its constraints or invariants;
- its characteristic failure cases and trade-offs.

Explain only the parts needed for the learner's goal. Verify technical claims when the domain or question requires current or high-stakes accuracy.

### 4. Choose the teaching representation

Use an analogy only when it preserves the concept's central causal structure. Prefer another representation when:

- the analogy would introduce another unfamiliar domain;
- the concept is already easier to show with a concrete example;
- formal precision is the learner's main goal;
- every plausible analogy implies a major falsehood.

For complex concepts, use one analogy for one mechanism rather than stretching it to cover the whole subject. Add a second analogy only when it repairs a clearly identified gap.

### 5. Teach on two tracks

Move between the familiar model and the real concept:

1. State the real concept and why it matters.
2. Describe the familiar situation briefly.
3. Map only the essential elements.
4. Run one concrete case through both models in the same order.
5. Translate the result back into real terminology.

Use a compact mapping table when there are at least three important correspondences. Otherwise, explain the mapping in prose.

Do not say that the concept *is* the analogy. Use language such as “this models one part of” or “the correspondence is.”

### 6. Remove the scaffolding

Re-explain the mechanism without the analogy. Depending on the topic and requested depth, use:

- a precise definition;
- a step-by-step technical trace;
- equations and interpretation of each term;
- code or pseudocode;
- a contrasting example or counterexample.

The learner should be able to discuss the concept without referring to the analogy after this step.

### 7. Mark boundaries and check transfer

Name the specific places where the mapping breaks. Include the most likely misconception the analogy could create.

When useful, end with one lightweight transfer check: ask the learner to predict a new case, distinguish two nearby concepts, or explain one causal step. Skip this for quick factual explanations or when the user did not ask for an interactive lesson.

## Analogy quality gate

Before using an analogy, confirm that it passes these checks:

1. **Familiarity**: the learner probably understands the source situation already.
2. **Causal fit**: corresponding events happen for structurally similar reasons.
3. **Dynamic fit**: the analogy can run through at least one representative input, process, and outcome.
4. **Coverage**: it captures the mechanism being taught, not merely one visible feature.
5. **Boundary clarity**: the important mismatches can be stated concretely.
6. **Low distortion**: it does not imply agency, intention, certainty, or physical behavior absent from the real concept.

If a candidate fails causal fit or low distortion, discard it. Do not rescue it with disclaimers.

## Select response depth

Follow the user's requested depth. Otherwise choose the smallest mode that accomplishes the learning goal.

### Quick

Use for a brief “what is this?” question:

- one precise definition;
- one compact analogy or concrete example;
- the most important boundary.

### Standard

Use by default for conceptual learning:

- problem and precise model;
- analogy and mapping;
- one worked case;
- return to real terminology;
- key boundaries.

### Deep

Use for advanced study, derivation, implementation, or comparison:

- prerequisites and precise mechanism;
- analogy as initial scaffolding;
- formalism, code, or detailed trace;
- counterexample and trade-offs;
- transfer check.

## Use stories selectively

Use a short story only when actors, time order, state changes, coordination, or failure recovery are central to the mechanism. Keep every event mapped to the concept.

Do not add characters, dialogue, or narrative detail to static definitions, mathematical objects, or quick explanations merely to make them entertaining.

## Prevent common failures

- Do not use a second specialized field as the “familiar” side unless the learner already knows it.
- Do not extend decorative details from the analogy into technical claims.
- Do not use biological or human-intention language for algorithms unless agency is explicitly part of the model.
- Do not hide uncertainty or exceptions to preserve a neat analogy.
- Do not overload the answer with every property of the concept.
- Do not repeat the same content as definition, story, summary, and table.

## Style

- Match the user's language; preserve useful original-language technical terms.
- Lead with what the concept is and why the learner should care.
- Prefer plain, precise language over jargon and theatrical prose.
- Use headings, tables, equations, code, and stories only when they improve comprehension.
- Keep the explanation proportional to the request.
