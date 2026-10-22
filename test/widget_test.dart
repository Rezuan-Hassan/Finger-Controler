import 'package:flutter_test/flutter_test.dart';
import 'package:finger_controler/main.dart';

void main() {
  testWidgets('App smoke test', (WidgetTester tester) async {
    await tester.pumpWidget(const FingerControlerApp());
    expect(find.text('Finger Controler'), findsOneWidget);
  });
}
