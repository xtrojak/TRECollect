#!/bin/bash
# Test summary script - extracts and displays test results
# Can be used after running tests locally or in CI
# Usage: ./test-summary.sh

echo "=========================================="
echo "Test Execution Summary"
echo "=========================================="

# Find test result directories (Gradle creates separate dirs for debug/release)
TEST_RESULTS_DIRS=()
if [ -d "app/build/test-results/test" ]; then
    TEST_RESULTS_DIRS+=("app/build/test-results/test")
fi
if [ -d "app/build/test-results/testDebugUnitTest" ]; then
    TEST_RESULTS_DIRS+=("app/build/test-results/testDebugUnitTest")
fi
if [ -d "app/build/test-results/testReleaseUnitTest" ]; then
    TEST_RESULTS_DIRS+=("app/build/test-results/testReleaseUnitTest")
fi

if [ ${#TEST_RESULTS_DIRS[@]} -gt 0 ]; then
    echo "Test Results:"
    
    # Aggregate results from all test result directories
    total=0
    failures=0
    errors=0
    skipped=0
    failed_files=()
    
    for dir in "${TEST_RESULTS_DIRS[@]}"; do
        # Count total tests
        dir_total=$(find "$dir" -name "*.xml" -exec grep -h "testsuite" {} \; 2>/dev/null | grep -oE 'tests="[0-9]+"' | awk -F'"' '{sum+=$2} END {print sum+0}' || echo "0")
        total=$((total + dir_total))
        
        # Count failures
        dir_failures=$(find "$dir" -name "*.xml" -exec grep -h "testsuite" {} \; 2>/dev/null | grep -oE 'failures="[0-9]+"' | awk -F'"' '{sum+=$2} END {print sum+0}' || echo "0")
        failures=$((failures + dir_failures))
        
        # Count errors
        dir_errors=$(find "$dir" -name "*.xml" -exec grep -h "testsuite" {} \; 2>/dev/null | grep -oE 'errors="[0-9]+"' | awk -F'"' '{sum+=$2} END {print sum+0}' || echo "0")
        errors=$((errors + dir_errors))
        
        # Count skipped
        dir_skipped=$(find "$dir" -name "*.xml" -exec grep -h "testsuite" {} \; 2>/dev/null | grep -oE 'skipped="[0-9]+"' | awk -F'"' '{sum+=$2} END {print sum+0}' || echo "0")
        skipped=$((skipped + dir_skipped))
        
        # Collect failed test files
        while IFS= read -r file; do
            if [ -n "$file" ]; then
                failed_files+=("$file")
            fi
        done < <(find "$dir" -name "*.xml" -exec grep -l "failure\|error" {} \; 2>/dev/null)
    done
    
    # Calculate passed
    passed=$((total - failures - errors - skipped))
    
    echo "  Total tests: ${total}"
    echo "  ✅ Passed: ${passed}"
    if [ "${skipped:-0}" -gt 0 ]; then
        echo "  ⏭️  Skipped: ${skipped}"
    fi
    if [ "${failures:-0}" -gt 0 ]; then
        echo "  ❌ Failures: ${failures}"
    else
        echo "  ✅ Failures: 0"
    fi
    if [ "${errors:-0}" -gt 0 ]; then
        echo "  ❌ Errors: ${errors}"
    else
        echo "  ✅ Errors: 0"
    fi
    
    echo ""
    
    # Overall status
    if [ "${failures:-0}" -gt 0 ] || [ "${errors:-0}" -gt 0 ]; then
        echo "❌ Some tests failed!"
        echo ""
        echo "Failed tests:"
        for file in "${failed_files[@]}"; do
            test_class=$(basename "$file" .xml)
            echo "  - $test_class"
        done
    else
        echo "✅ All tests passed!"
    fi
else
    echo "⚠️  Test results directory not found"
    echo "  This might mean tests didn't run or failed before generating results"
    echo "  Expected locations:"
    echo "    - app/build/test-results/test"
    echo "    - app/build/test-results/testDebugUnitTest"
    echo "    - app/build/test-results/testReleaseUnitTest"
fi

echo ""
echo "=========================================="
echo "Test Reports"
echo "=========================================="

# Check for HTML reports in common locations
HTML_REPORT_FOUND=false
for report_path in "app/build/reports/tests/test/index.html" "app/build/reports/tests/testDebugUnitTest/index.html" "app/build/reports/tests/testReleaseUnitTest/index.html"; do
    if [ -f "$report_path" ]; then
        echo "✅ HTML report generated"
        echo "  Location: $report_path"
        if [ -z "$CI" ]; then
            # Only show open command when not in CI
            echo ""
            echo "To view in browser, run:"
            echo "  open $report_path"
            echo "  (or on Linux: xdg-open $report_path)"
        fi
        HTML_REPORT_FOUND=true
        break
    fi
done

if [ "$HTML_REPORT_FOUND" = false ]; then
    echo "⚠️  HTML report not found"
    echo "  Checked locations:"
    echo "    - app/build/reports/tests/test/index.html"
    echo "    - app/build/reports/tests/testDebugUnitTest/index.html"
    echo "    - app/build/reports/tests/testReleaseUnitTest/index.html"
fi

# Count XML files in all test result directories
xml_count=0
for dir in "${TEST_RESULTS_DIRS[@]}"; do
    count=$(find "$dir" -name "*.xml" 2>/dev/null | wc -l | tr -d ' ')
    xml_count=$((xml_count + count))
done
if [ "$xml_count" -gt 0 ]; then
    echo "  XML reports: $xml_count file(s) across ${#TEST_RESULTS_DIRS[@]} directory(ies)"
fi

echo ""
echo "=========================================="
