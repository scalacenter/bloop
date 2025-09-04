# Compilation Trace Feature

The compilation trace feature allows you to keep a detailed record of compilation activities in a `.bloop/compilation.trace.json` file, similar to Metals trace files. This is particularly useful for debugging transient compilation failures.

## Enabling Compilation Trace

The compilation trace feature is controlled by the `enableCompilationTrace` setting in the workspace settings. This can be set in the `.bloop/bloop.settings.json` file:

```json
{
  "enableCompilationTrace": true
}
```

Alternatively, this setting can be configured by build tools like Metals through the BSP protocol.

## Trace File Location

When enabled, the compilation trace is written to `.bloop/compilation.trace.json` in the workspace directory.

## Trace File Format

The trace file contains JSON data with the following structure:

```json
{
  "entries": [
    {
      "project": "project-name",
      "startTime": "2023-08-07T16:30:00.123Z",
      "endTime": "2023-08-07T16:30:02.456Z",
      "compiledFiles": [
        "/path/to/source1.scala",
        "/path/to/source2.scala"
      ],
      "diagnostics": [
        {
          "file": "/path/to/source1.scala",
          "severity": "warning",
          "message": "Warning message",
          "line": 42,
          "column": 10
        }
      ],
      "artifacts": [
        {
          "source": "/path/to/classes/dir",
          "destination": "/path/to/output/dir", 
          "artifactType": "class"
        },
        {
          "source": "/path/to/analysis.analysis",
          "destination": "/path/to/analysis/output.analysis",
          "artifactType": "analysis"
        }
      ],
      "isNoOp": false,
      "result": "Success"
    }
  ]
}
```

## Trace Information

The compilation trace captures the following information for each compilation:

1. **What files are being compiled**: Listed in the `compiledFiles` array
2. **What diagnostics were produced**: Compilation errors, warnings, and info messages in the `diagnostics` array
3. **Which artifacts were copied where**: Class files, analysis files, and resources in the `artifacts` array  
4. **When no-op compilation was done**: Indicated by the `isNoOp` field

## Result Types

The `result` field can have the following values:
- `"Success"`: Compilation completed successfully
- `"Failed"`: Compilation failed with errors
- `"Cancelled"`: Compilation was cancelled
- `"Best Effort Failed"`: Compilation failed but best-effort mode produced some artifacts

## Integration with Metals

This feature is designed to be enabled by Metals and other BSP clients to provide better debugging capabilities for compilation issues. When enabled, it provides detailed information that can help diagnose:

- Incremental compilation issues
- Missing dependencies
- File copying problems
- Timing-related compilation failures

## Performance Impact

The compilation trace feature has minimal performance impact when disabled (default). When enabled, it adds a small overhead for collecting and writing trace information, but this is negligible compared to the compilation time itself.

## Example Usage

1. Enable the feature in your workspace settings
2. Run a compilation
3. Examine the `.bloop/compilation.trace.json` file to understand what happened during compilation

This is particularly useful for debugging cases where compilation fails intermittently or when you need to understand the exact sequence of compilation events.