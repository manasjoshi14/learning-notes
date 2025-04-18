# Redis Source Code Exploration Prompts

This document contains a collection of prompts designed to help explore and understand the Redis source code using Claude as a guide.

## Initial Exploration Prompt

Use this prompt to begin exploring the Redis codebase with Claude:

```
You are an expert software engineer and teacher who understands the Redis codebase deeply. I am a complete beginner to Redis's internal implementation, but I am comfortable with C and general computer science concepts.

I have loaded all the Redis code into Cursor and indexed it. I want you to help me understand Redis, from the basics to advanced internals, acting as a tutor and step-by-step guide. Please do the following:

1. Provide a high-level overview of how Redis works as a system, including its main components/modules and how they interrelate (with reference to key files or directories in the codebase).
2. Suggest a step-by-step roadmap for reading and understanding the codebase, starting from the most central/simple components and moving to more advanced or niche parts.
3. For the first step on that roadmap, explain:
   - Which files/folders are relevant.
   - What are the main structures, functions, or algorithms I should look for.
   - Summarize and annotate key code snippets (give line numbers if possible) and explain in simple language, as if teaching a beginner.
4. Suggest follow-up questions or "milestones" a learner should try to answer/achieve before moving to the next step.
5. As I follow this process, I may ask deeper questions or for clarification on specific files; please always answer with clear, concise, and progressively more advanced explanations as I learn.

Let's start with the high-level overview and suggested roadmap only. When you're ready, prompt me to proceed to step 1!
```

## Question Documentation Prompt

Use this prompt to instruct Claude to document your questions and answers:

```
okay now I will be asking you questions as I go through the document and code. Make sure to add the question and corresponding answer in the qna document
```

## Continuation Prompt

Use this prompt when you want to continue exploring a specific area of Redis after having already done some exploration:

```
I'm working on exploring and documenting the Redis codebase. I've been creating detailed markdown files in the deep-dives/ directory to document different aspects of Redis's implementation, along with companion Q&A files that record questions and answers about each topic.

Please examine the files in my workspace to understand what I've been working on so far. Look at the file structure and content of the markdown files to understand:
1. What components I've already explored 
2. What documentation patterns I'm using
3. The current documentation structure
4. Which topic I was most recently focusing on

Then, continue helping me explore Redis, focusing on the most recent component we were working on. When I ask questions, please:
1. Answer them thoroughly
2. Add both my question and your answer to the appropriate *_qna.md file for that component
3. Update the table of contents in that Q&A file to include the new question

Let's continue our exploration of Redis, building on the work we've already done. Review the files first to understand where we left off.
```

## Fact Check Prompt
```
Please thoroughly fact-check and review <> . For the entire document:

1. Identify and correct any inaccuracies or unclear statements.
2. For each major claim, explanation, or factual statement, add a citation or reference to the relevant part of the Redis codebase (file names, directory paths, and line numbers where possible) or official Redis documentation.
3. If you cannot verify a statement directly from the code or official docs, clearly mark it as "unverified" or "needs review."
4. Ensure the final version is clear, accurate, well-structured, and each point is properly referenced with in-line citations.

Do the required edits and improvements in the document
```