//
//  ExampleAVAudioEngineDevice.h
//  AudioDeviceExample
//
//  Copyright © 2018 Twilio Inc. All rights reserved.
//

#import <TwilioVideo/TwilioVideo.h>

@protocol MetronomeDelegate;

NS_CLASS_AVAILABLE(NA, 11_0)
@interface ExampleAVAudioEngineDevice : NSObject <TVIAudioDevice>

- (instancetype)startMetronome;
- (void)stopMetronome;
- (void)setTempo: (float)tempo ;

@property(weak, nullable) id<MetronomeDelegate> delegate;

@end

@protocol MetronomeDelegate <NSObject>
@optional
- (void)metronomeTicking:(ExampleAVAudioEngineDevice * _Nonnull)metronome bar:(SInt32)bar beat:(SInt32)beat;
@end
