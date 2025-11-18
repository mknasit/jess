# Stream API Test Cases

This directory contains test cases for Stream API and modern Java features.

## Test Files

1. **StreamForEach.java** - Tests `stream().forEach()` with lambda
2. **StreamMap.java** - Tests `stream().map()` with method reference
3. **StreamFilter.java** - Tests `stream().filter().collect()`
4. **CollectionForEach.java** - Tests `collection.forEach()` with lambda

## Expected Behavior

The tool should:
- Stub `stream()` method on unresolved collection types, returning `Stream<T>`
- Stub `forEach()` method on collections, accepting `Consumer<T>`
- Handle method references like `String::length` in map operations
- Stub functional interfaces (Consumer, Function, Predicate) when used as parameters

