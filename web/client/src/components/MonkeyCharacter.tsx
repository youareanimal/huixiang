type ArmPose = 'down' | 'mouth' | 'ear';

interface ArmProps {
  side: 'left' | 'right';
  pose: ArmPose;
}

function Arm({ side, pose }: ArmProps) {
  const left = side === 'left';
  let path = '';
  let x = 0;
  let y = 0;
  let rotation = 0;

  if (pose === 'down') {
    path = left
      ? 'M50 130 C36 138 29 155 20 180'
      : 'M110 130 C124 138 131 155 140 180';
    x = left ? 20 : 140;
    y = 184;
    rotation = left ? -28 : 28;
  } else if (pose === 'mouth') {
    path = left
      ? 'M50 130 C49 147 55 163 64 176'
      : 'M110 130 C111 147 105 163 96 176';
    x = left ? 64 : 96;
    y = 178;
    rotation = left ? 35 : -35;
  } else {
    path = left
      ? 'M50 130 C37 134 32 145 40 153'
      : 'M110 130 C123 134 128 145 120 153';
    x = left ? 40 : 120;
    y = 153;
    rotation = left ? -12 : 12;
  }

  return (
    <g className={'monkey-arm monkey-arm-' + side}>
      <path d={path} className="monkey-limb-outline" />
      <path d={path} className="monkey-limb-fill" />
      <g transform={'translate(' + x + ' ' + y + ') rotate(' + rotation + ')'}>
        <path
          className="monkey-hand"
          d="M-10 -4 C-15 -12 -8 -17 -3 -11 L0 -8 C2 -18 9 -16 9 -7 L13 -5 C20 -8 22 2 15 9 C8 16 -5 15 -11 7 C-15 3 -14 -1 -10 -4Z"
        />
        <path className="monkey-fingers" d="M-7 -6 L-10 -12 M0 -7 L0 -14 M7 -5 L11 -10" />
      </g>
    </g>
  );
}

interface Props {
  nickname: string;
  micOpen: boolean;
  inputMuted: boolean;
  outputMuted: boolean;
  talking: boolean;
  variant: number;
}

export default function MonkeyCharacter({
  nickname,
  micOpen,
  inputMuted,
  outputMuted,
  talking,
  variant,
}: Props) {
  const leftPose: ArmPose = inputMuted ? 'mouth' : outputMuted ? 'ear' : 'down';
  const rightPose: ArmPose = inputMuted && outputMuted ? 'ear' : inputMuted ? 'mouth' : outputMuted ? 'ear' : 'down';
  const stateText = inputMuted && outputMuted
    ? '麦克风和扬声器均已关闭'
    : inputMuted
      ? '麦克风已关闭'
      : outputMuted
        ? '扬声器已关闭'
        : talking
          ? '正在说话'
          : '麦克风已开启';

  return (
    <div className={'monkey-figure' + (talking ? ' is-speaking' : '')}>
      <svg
        className={
          'monkey-svg monkey-variant-' + (variant % 4) +
          (micOpen ? ' is-mic-open' : '') +
          (talking ? ' is-speaking' : '')
        }
        viewBox="0 0 160 224"
        role="img"
        aria-label={nickname + ' 的猴子，' + stateText}
      >
        <title>{nickname}：{stateText}</title>

        <g className="monkey-tail">
          <path
            className="monkey-tail-outline"
            d="M80 96 C80 77 77 59 77 43 C77 22 98 15 108 29 C118 43 108 57 96 53 C90 51 88 45 92 40"
          />
          <path
            className="monkey-tail-fill"
            d="M80 96 C80 77 77 59 77 43 C77 22 98 15 108 29 C118 43 108 57 96 53 C90 51 88 45 92 40"
          />
        </g>

        <g className="monkey-legs">
          <path className="monkey-limb-outline" d="M66 96 C54 86 50 74 40 62" />
          <path className="monkey-limb-fill" d="M66 96 C54 86 50 74 40 62" />
          <path className="monkey-limb-outline" d="M94 96 C106 86 110 74 120 62" />
          <path className="monkey-limb-fill" d="M94 96 C106 86 110 74 120 62" />
          <path className="monkey-foot" d="M40 62 C31 50 20 49 16 57 C12 65 20 73 30 69 C35 67 38 65 40 62Z" />
          <path className="monkey-toes" d="M18 59 L11 55 M23 54 L20 48 M30 54 L30 47" />
          <path className="monkey-foot" d="M120 62 C129 50 140 49 144 57 C148 65 140 73 130 69 C125 67 122 65 120 62Z" />
          <path className="monkey-toes" d="M142 59 L149 55 M137 54 L140 48 M130 54 L130 47" />
        </g>

        <ellipse className="monkey-body" cx="80" cy="126" rx="35" ry="48" />
        <ellipse className="monkey-belly" cx="80" cy="132" rx="22" ry="34" />

        <g className="monkey-head">
          <ellipse className="monkey-ear" cx="37" cy="159" rx="18" ry="20" />
          <ellipse className="monkey-ear" cx="123" cy="159" rx="18" ry="20" />
          <ellipse className="monkey-ear-inner" cx="38" cy="160" rx="9" ry="11" />
          <ellipse className="monkey-ear-inner" cx="122" cy="160" rx="9" ry="11" />
          <ellipse className="monkey-head-fur" cx="80" cy="166" rx="45" ry="39" />
          <path className="monkey-tuft" d="M54 139 Q59 126 67 137 Q73 124 80 136 Q88 124 94 137 Q103 127 107 142" />
          <ellipse className="monkey-face" cx="80" cy="169" rx="32" ry="27" />
          <ellipse className="monkey-cheek" cx="61" cy="177" rx="8" ry="5" />
          <ellipse className="monkey-cheek" cx="99" cy="177" rx="8" ry="5" />

          <path className="monkey-brow" d="M57 151 Q67 143 76 151" />
          <path className="monkey-brow" d="M84 151 Q94 143 103 151" />
          <ellipse className="monkey-eye" cx="67" cy="158" rx="8" ry="7" />
          <ellipse className="monkey-eye" cx="93" cy="158" rx="8" ry="7" />
          <ellipse className="monkey-pupil" cx="69" cy="160" rx="3.4" ry="4.2" />
          <ellipse className="monkey-pupil" cx="91" cy="160" rx="3.4" ry="4.2" />
          <ellipse className="monkey-nose" cx="80" cy="171" rx="5.5" ry="3.7" />
          <path className="monkey-nostrils" d="M77 171 L78 172 M82 171 L83 172" />

          <path className="monkey-mouth-closed" d="M65 181 Q80 193 95 181" />
          <g className="monkey-mouth-open">
            <ellipse cx="80" cy="183" rx="14" ry="11" />
            <path className="monkey-teeth" d="M69 179 Q80 174 91 179 Q87 184 80 184 Q73 184 69 179Z" />
            <path className="monkey-tongue" d="M71 188 Q80 193 89 188 Q85 199 80 198 Q74 198 71 188Z" />
          </g>
        </g>

        <Arm side="left" pose={leftPose} />
        <Arm side="right" pose={rightPose} />
      </svg>
    </div>
  );
}
