import 'package:flutter/material.dart';
import 'pollinet_sdk.dart';

void main() {
  runApp(const MyApp());
}

class MyApp extends StatelessWidget {
  const MyApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Pollinet Flutter',
      theme: ThemeData(
        colorScheme: ColorScheme.fromSeed(seedColor: Colors.deepPurple),
        useMaterial3: true,
      ),
      home: const PollinetHomePage(),
    );
  }
}

class PollinetHomePage extends StatefulWidget {
  const PollinetHomePage({super.key});

  @override
  State<PollinetHomePage> createState() => _PollinetHomePageState();
}

class _PollinetHomePageState extends State<PollinetHomePage> {
  String _status = 'Not initialized';
  bool _isInitialized = false;

  Future<void> _initializeSdk() async {
    try {
      setState(() => _status = 'Initializing...');
      final success = await PollinetSdk.initialize();
      if (success) {
        final version = await PollinetSdk.version();
        setState(() {
          _isInitialized = true;
          _status = 'Initialized (v$version)';
        });
      } else {
        setState(() => _status = 'Initialization failed');
      }
    } catch (e) {
      setState(() => _status = 'Error: $e');
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        backgroundColor: Theme.of(context).colorScheme.inversePrimary,
        title: const Text('Pollinet SDK Demo'),
      ),
      body: Center(
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Text(
              'SDK Status:',
              style: Theme.of(context).textTheme.headlineSmall,
            ),
            const SizedBox(height: 20),
            Text(
              _status,
              style: Theme.of(context).textTheme.bodyLarge,
            ),
            const SizedBox(height: 40),
            ElevatedButton(
              onPressed: _isInitialized ? null : _initializeSdk,
              child: Text(_isInitialized ? 'SDK Initialized' : 'Initialize SDK'),
            ),
          ],
        ),
      ),
    );
  }
}
