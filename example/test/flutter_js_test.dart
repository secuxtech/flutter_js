import 'package:flutter/foundation.dart';
import 'package:flutter_js/flutter_js.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  late JavascriptRuntime jsRuntime;

  setUp(() {
    jsRuntime = getJavascriptRuntime();
  });

  tearDown(() {
    try {
      jsRuntime.dispose();
    } on Error catch (_) {}
  });

  test('evaluate javascript', () {
    final result = jsRuntime.evaluate('Math.pow(5,3)');
    if (kDebugMode) {
      print('${result.rawResult}, ${result.stringResult}');
      print(
          '${result.rawResult.runtimeType}, ${result.stringResult.runtimeType}');
    }
    expect(result.rawResult, equals(125));
    expect(result.stringResult, equals('125'));
  });

  test('marks and resolves a javascript promise', () async {
    final pending = jsRuntime.evaluate(
      '(async function () { return "promise resolved"; })();',
    );

    expect(pending.isPromise, isTrue);

    final resolved = await jsRuntime.handlePromise(
      pending,
      timeout: const Duration(seconds: 5),
    );

    expect(resolved.stringResult, contains('promise resolved'));
  });

  test('provides compatibility built-ins', () {
    final result = jsRuntime.evaluate('''
      Object.hasOwn({ value: 1 }, 'value') &&
      [1, 2, 3].at(-1) === 3;
    ''');

    expect(result.isError, isFalse);
    expect(result.stringResult, equals('true'));
  });
}
