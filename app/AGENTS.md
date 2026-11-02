# Gemini API implementation rules

## Mandatory rules

When creating or modifying code that calls the Google Gemini API, follow these rules.

### 1. Verify the model before writing code
- Never choose a Gemini model name from memory, autocomplete, old tutorials, or existing sample code without verification.
- Check the current official model catalog:
  https://ai.google.dev/gemini-api/docs/models
- Check the official deprecation schedule:
  https://ai.google.dev/gemini-api/docs/deprecations
- Use a stable model explicitly listed as available for the intended API. Prefer a current stable model over preview or experimental models unless the project requires otherwise.
- Do not assume that a model shown in Android Studio's built-in AI model selector is also available to the app through the Gemini Developer API.

### 2. Never introduce obsolete models
- Do not use retired model IDs such as `gemini-1.5-flash`, `gemini-1.5-pro`, `gemini-2.0-flash`, or `gemini-2.0-flash-lite` in active API calls.
- Do not replace an obsolete model with another model based only on its name or generation number. Verify the replacement against official documentation.
- If the official status cannot be verified, stop and report the uncertainty instead of guessing.

### 3. Identify the actual integration first
Before editing code, inspect the project's dependencies, imports, configuration, and existing API calls to determine whether it uses:
- Gemini Developer API / Google AI SDK
- Firebase AI Logic
- Vertex AI
- A backend server that calls Gemini

Follow the official documentation for the integration actually used by this project. Do not migrate between integrations unless explicitly requested.

### 4. Centralize the model configuration
- Keep the model ID in one configuration location wherever practical.
- Avoid duplicating model strings across Activities, ViewModels, repositories, and services.
- Preserve the existing architecture and change only what is necessary.
- Never put API keys or other secrets in source control or expose them in a distributed Android app.

### 5. Validate before declaring success
- Search the project for obsolete model IDs and unintended duplicate model declarations.
- Verify SDK and model compatibility using official documentation.
- Run the relevant build and tests when possible.
- Report the exact model ID, integration type, official verification source, and any remaining uncertainty.

### 6. Be honest about verification
Never claim that a model is current, available, or tested unless that has actually been verified. If external documentation is inaccessible, explicitly say so.